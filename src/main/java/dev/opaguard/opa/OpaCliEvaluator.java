package dev.opaguard.opa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import dev.opaguard.config.GuardProperties;
import dev.opaguard.domain.Measurement;
import dev.opaguard.exception.GuardException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class OpaCliEvaluator implements PolicyEvaluator {
    private final ObjectMapper objectMapper;
    private final String executable;

    public OpaCliEvaluator(ObjectMapper objectMapper, GuardProperties properties) {
        this.objectMapper = objectMapper;
        this.executable = properties.opaExecutable();
    }

    @Override
    public Measurement evaluate(Path policyPath, String query, JsonNode input, Duration timeout) {
        validatePolicyPath(policyPath);
        List<String> command = List.of(
                executable, "eval",
                "--format=json",
                "--data", policyPath.toAbsolutePath().normalize().toString(),
                "--stdin-input",
                query);

        long startedAt = System.nanoTime();
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            Process activeProcess = process;
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(
                    () -> readAll(activeProcess.getInputStream()));
            CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(
                    () -> readAll(activeProcess.getErrorStream()));

            try (ProcessMetricsSampler sampler = new ProcessMetricsSampler(process)) {
                objectMapper.writeValue(process.getOutputStream(), input);
                process.getOutputStream().close();

                if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new GuardException("OPA evaluation timed out after " + timeout.toSeconds() + " seconds");
                }

                long elapsed = System.nanoTime() - startedAt;
                byte[] output = stdout.get(5, TimeUnit.SECONDS);
                String errorOutput = new String(stderr.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
                if (process.exitValue() != 0) {
                    throw new GuardException("OPA exited with code " + process.exitValue() + ": " + errorOutput);
                }
                return new Measurement(elapsed, sampler.cpuNanos(), sampler.peakRssBytes(), parseDecision(output));
            }
        } catch (GuardException exception) {
            throw exception;
        } catch (Exception exception) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            throw new GuardException("Unable to execute OPA command '" + executable + "': " + exception.getMessage(), exception);
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

    private static byte[] readAll(java.io.InputStream stream) {
        try {
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new GuardException("Failed to capture OPA output", exception);
        }
    }

    private static void validatePolicyPath(Path policyPath) {
        if (policyPath == null || !Files.exists(policyPath)) {
            throw new GuardException("Policy path does not exist: " + policyPath);
        }
    }
}
