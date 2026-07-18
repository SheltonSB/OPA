package dev.opaguard.opa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import dev.opaguard.config.GuardProperties;
import dev.opaguard.domain.Measurement;
import dev.opaguard.exception.GuardException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Isolated {@link PolicyEvaluator} that invokes a fresh {@code opa eval} process per sample.
 *
 * <p>Commands are constructed as argument lists, policy roots and queries are
 * validated, output is bounded, and timed-out process trees are terminated.</p>
 *
 * @author Shelton Bumhe
 */
@Component
public class OpaCliEvaluator implements PolicyEvaluator {
    static final int MAX_PROCESS_OUTPUT_BYTES = 16 * 1024 * 1024;
    private final ObjectMapper objectMapper;
    private final String executable;

    /**
     * Creates an OPA CLI adapter.
     *
     * @param objectMapper constrained JSON mapper
     * @param properties application configuration containing the trusted executable
     */
    public OpaCliEvaluator(ObjectMapper objectMapper, GuardProperties properties) {
        this.objectMapper = objectMapper;
        this.executable = properties.opaExecutable();
    }

    @Override
    public Measurement evaluate(Path policyPath, String query, JsonNode input, Duration timeout) {
        Path validatedPolicyPath = validatePolicyPath(policyPath);
        validateQuery(query);
        byte[] serializedInput = serializeInput(input);
        List<String> command = List.of(
                executable, "eval",
                "--format=json",
                "--data", validatedPolicyPath.toString(),
                "--stdin-input",
                query);

        long startedAt = System.nanoTime();
        Process process = null;
        try (ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            process = new ProcessBuilder(command).start();
            Process activeProcess = process;
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(
                    () -> readBounded(activeProcess.getInputStream()), ioExecutor);
            CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(
                    () -> readBounded(activeProcess.getErrorStream()), ioExecutor);

            try (ProcessMetricsSampler sampler = new ProcessMetricsSampler(process)) {
                IOException inputFailure = null;
                try (OutputStream processInput = process.getOutputStream()) {
                    processInput.write(serializedInput);
                } catch (IOException exception) {
                    // A process that rejects a policy may exit before consuming stdin. Preserve
                    // that failure, but read its exit code and stderr before deciding which error
                    // is useful to the caller.
                    inputFailure = exception;
                }

                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    terminateProcessTree(process);
                    throw new GuardException("OPA evaluation timed out after " + timeout.toSeconds() + " seconds");
                }

                long elapsed = System.nanoTime() - startedAt;
                byte[] output = stdout.get(5, TimeUnit.SECONDS);
                String errorOutput = new String(stderr.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
                if (process.exitValue() != 0) {
                    throw new GuardException("OPA exited with code " + process.exitValue() + ": " + errorOutput);
                }
                if (inputFailure != null) {
                    throw new GuardException("OPA closed its input stream before accepting the evaluation input",
                            inputFailure);
                }
                return new Measurement(elapsed, sampler.cpuNanos(), sampler.peakRssBytes(), parseDecision(output));
            }
        } catch (GuardException exception) {
            throw exception;
        } catch (Exception exception) {
            if (process != null && process.isAlive()) {
                terminateProcessTree(process);
            }
            throw new GuardException("Unable to execute OPA command '" + executable + "': " + exception.getMessage(), exception);
        }
    }

    private byte[] serializeInput(JsonNode input) {
        if (input == null) {
            throw new GuardException("OPA evaluation input must not be null");
        }
        try {
            return objectMapper.writeValueAsBytes(input);
        } catch (IOException exception) {
            throw new GuardException("Unable to serialize OPA evaluation input", exception);
        }
    }

    private JsonNode parseDecision(byte[] output) throws IOException {
        JsonNode root = objectMapper.readTree(output);
        JsonNode expressions = root.path("result").path(0).path("expressions");
        if (!expressions.isArray() || expressions.isEmpty()) {
            return NullNode.getInstance();
        }
        return expressions.path(0).path("value");
    }

    private static byte[] readBounded(java.io.InputStream stream) {
        try {
            byte[] bytes = stream.readNBytes(MAX_PROCESS_OUTPUT_BYTES + 1);
            if (bytes.length > MAX_PROCESS_OUTPUT_BYTES) {
                throw new GuardException("OPA output exceeded the 16 MiB safety limit");
            }
            return bytes;
        } catch (IOException exception) {
            throw new GuardException("Failed to capture OPA output", exception);
        }
    }

    private static Path validatePolicyPath(Path policyPath) {
        if (policyPath == null || !Files.exists(policyPath)) {
            throw new GuardException("Policy path does not exist: " + policyPath);
        }
        try {
            if (Files.isSymbolicLink(policyPath)) {
                throw new GuardException("Symbolic links are not accepted as policy roots: " + policyPath);
            }
            if (Files.isDirectory(policyPath)) {
                try (var paths = Files.walk(policyPath)) {
                    if (paths.anyMatch(Files::isSymbolicLink)) {
                        throw new GuardException("Policy trees must not contain symbolic links: " + policyPath);
                    }
                }
            }
            return policyPath.toRealPath();
        } catch (IOException exception) {
            throw new GuardException("Unable to resolve policy path: " + policyPath, exception);
        }
    }

    private static void validateQuery(String query) {
        if (query == null || !query.matches("data(?:\\.[A-Za-z_][A-Za-z0-9_-]*)+")) {
            throw new GuardException("OPA query must be a fully qualified data path");
        }
    }

    private static void terminateProcessTree(Process process) {
        process.descendants().forEach(handle -> {
            handle.destroy();
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        });
        process.destroy();
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
