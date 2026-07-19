package dev.opaguard.opa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.opaguard.config.GuardProperties;
import dev.opaguard.exception.GuardException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpaCliEvaluatorTest {
    @TempDir
    Path tempDir;

    @Test
    void executesCliAndExtractsDecision() throws Exception {
        Path executable = fakeOpa("#!/bin/sh\ncat >/dev/null\nprintf '%s' '{\"result\":[{\"expressions\":[{\"value\":{\"allowed\":true}}]}]}'\n");
        Path policy = Files.createDirectory(tempDir.resolve("policy"));
        OpaCliEvaluator evaluator = new OpaCliEvaluator(new ObjectMapper(), properties(executable));
        ObjectNode input = new ObjectMapper().createObjectNode().put("user", "alice");

        var measurement = evaluator.evaluate(policy, "data.authz", input, Duration.ofSeconds(2));

        assertThat(measurement.decision().path("allowed").asBoolean()).isTrue();
        assertThat(measurement.wallTimeNanos()).isPositive();
        assertThat(measurement.peakMemoryBytes()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void includesOpaErrorWhenCommandFails() throws Exception {
        Path executable = fakeOpa("#!/bin/sh\necho 'rego parse error' >&2\nexit 2\n");
        Path policy = Files.createDirectory(tempDir.resolve("broken-policy"));
        OpaCliEvaluator evaluator = new OpaCliEvaluator(new ObjectMapper(), properties(executable));

        assertThatThrownBy(() -> evaluator.evaluate(
                policy, "data.authz", new ObjectMapper().createObjectNode(), Duration.ofSeconds(2)))
                .isInstanceOf(GuardException.class)
                .hasMessageContaining("rego parse error");
    }

    private Path fakeOpa(String script) throws Exception {
        Path executable = tempDir.resolve("opa-" + System.nanoTime());
        Files.writeString(executable, script);
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return executable;
    }

    private GuardProperties properties(Path executable) {
        return new GuardProperties(
                executable.toString(), "data.authz", Path.of("baseline"), Path.of("candidate"),
                Path.of("dataset.json"), 10, 10, 1, 0,
                Path.of("report.md"), Path.of("report.json"), 2, true, Path.of("policy"));
    }
}
