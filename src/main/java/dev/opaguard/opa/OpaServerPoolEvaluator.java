package dev.opaguard.opa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import dev.opaguard.config.GuardProperties;
import dev.opaguard.domain.Measurement;
import dev.opaguard.exception.GuardException;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Worker-mode evaluator backed by a bounded pool of long-lived loopback OPA servers.
 *
 * <p>Each real policy path maps to an isolated runtime. Least-recently-used
 * eviction caps child-process count, and all HTTP responses are size bounded.</p>
 *
 * @author Shelton Bumhe
 */
@Component
@Primary
@ConditionalOnProperty(name = "opa-guard.mode", havingValue = "worker")
public class OpaServerPoolEvaluator implements PolicyEvaluator, RuntimeTelemetryProvider {
    private static final int MAX_RUNTIMES = 8;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private final String executable;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<Path, RuntimeProcess> runtimes = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * Creates a worker evaluator and its loopback-only HTTP client.
     *
     * @param properties application configuration containing the OPA executable
     * @param objectMapper constrained JSON mapper
     */
    public OpaServerPoolEvaluator(GuardProperties properties, ObjectMapper objectMapper) {
        this.executable = properties.opaExecutable();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @Override
    public Measurement evaluate(Path policyPath, String query, JsonNode input, Duration timeout) {
        RuntimeProcess runtime = runtime(policyPath);
        long cpuBefore = runtime.cpuNanos();
        long started = System.nanoTime();
        try {
            byte[] body = objectMapper.writeValueAsBytes(Map.of("input", input));
            HttpRequest request = HttpRequest.newBuilder(runtime.decisionUri(query))
                    .timeout(timeout).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBytes;
            try (InputStream stream = response.body()) { responseBytes = readBounded(stream); }
            if (response.statusCode() != 200) throw new GuardException("OPA server returned status " + response.statusCode());
            long elapsed = System.nanoTime() - started;
            JsonNode result = objectMapper.readTree(responseBytes).path("result");
            long residentMemory = 0;
            try { residentMemory = ProcessMetricsSampler.currentRssBytes(runtime.process().pid()); }
            catch (IOException | NumberFormatException ignored) { }
            return new Measurement(elapsed, Math.max(0, runtime.cpuNanos() - cpuBefore), residentMemory,
                    0, 0, result.isMissingNode() ? NullNode.getInstance() : result);
        } catch (GuardException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new GuardException("OPA runtime evaluation failed", exception);
        }
    }

    @Override
    public RuntimeTelemetry snapshot(Path policyPath) {
        RuntimeProcess runtime = runtime(policyPath);
        OpaPrometheusMetrics.Snapshot metrics = runtime.metrics(Duration.ofSeconds(5));
        return new RuntimeTelemetry(metrics.allocatedBytes(), metrics.gcPauseNanos());
    }

    private RuntimeProcess runtime(Path policyPath) {
        try {
            Path real = policyPath.toRealPath();
            synchronized (runtimes) {
                RuntimeProcess existing = runtimes.get(real);
                if (existing != null && existing.process().isAlive()) return existing;
                if (existing != null) runtimes.remove(real);
                if (runtimes.size() >= MAX_RUNTIMES) {
                    var iterator = runtimes.entrySet().iterator();
                    RuntimeProcess eldest = iterator.next().getValue();
                    iterator.remove();
                    eldest.process().destroy();
                    if (eldest.process().isAlive()) eldest.process().destroyForcibly();
                }
                RuntimeProcess created = start(real);
                runtimes.put(real, created);
                return created;
            }
        } catch (IOException exception) {
            throw new GuardException("Unable to resolve OPA policy runtime", exception);
        }
    }

    private RuntimeProcess start(Path policyPath) {
        int port = loopbackPort();
        try {
            Process process = new ProcessBuilder(executable, "run", "--server", "--addr=127.0.0.1:" + port,
                    "--log-level=error", policyPath.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            RuntimeProcess runtime = new RuntimeProcess(process, port);
            for (int attempt = 0; attempt < 50; attempt++) {
                if (!process.isAlive()) throw new GuardException("OPA runtime exited during startup");
                try {
                    HttpRequest health = HttpRequest.newBuilder(runtime.baseUri().resolve("/health"))
                            .timeout(Duration.ofMillis(250)).GET().build();
                    if (httpClient.send(health, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) return runtime;
                } catch (IOException ignored) { }
                Thread.sleep(100);
            }
            process.destroyForcibly();
            throw new GuardException("OPA runtime health check timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new GuardException("OPA runtime startup interrupted", interrupted);
        } catch (IOException exception) {
            throw new GuardException("Unable to start OPA runtime", exception);
        }
    }

    private int loopbackPort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new GuardException("Unable to allocate loopback OPA port", exception);
        }
    }

    private byte[] readBounded(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) throw new GuardException("OPA server response exceeded 16 MiB");
        return bytes;
    }

    @PreDestroy
    void close() {
        runtimes.values().forEach(runtime -> {
            runtime.process().destroy();
            if (runtime.process().isAlive()) runtime.process().destroyForcibly();
        });
        runtimes.clear();
    }

    private final class RuntimeProcess {
        private final Process process;
        private final int port;
        RuntimeProcess(Process process, int port) { this.process = process; this.port = port; }
        Process process() { return process; }
        URI baseUri() { return URI.create("http://127.0.0.1:" + port); }
        URI decisionUri(String query) {
            if (query == null || !query.matches("data(?:\\.[A-Za-z_][A-Za-z0-9_-]*)+"))
                throw new GuardException("Invalid OPA decision query");
            return baseUri().resolve("/v1/data/" + query.substring(5).replace('.', '/'));
        }
        OpaPrometheusMetrics.Snapshot metrics(Duration timeout) {
            try {
                HttpRequest request = HttpRequest.newBuilder(baseUri().resolve("/metrics"))
                        .timeout(timeout).GET().build();
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream stream = response.body()) {
                    return OpaPrometheusMetrics.parse(new String(readBounded(stream), java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception exception) {
                return new OpaPrometheusMetrics.Snapshot(0, 0, 0);
            }
        }
        long cpuNanos() {
            try { return process.info().totalCpuDuration().orElse(Duration.ZERO).toNanos(); }
            catch (RuntimeException denied) { return 0; }
        }
    }
}
