package dev.opaguard.platform.domain;

import com.fasterxml.jackson.databind.JsonNode;
import dev.opaguard.domain.BenchmarkMetrics;
import dev.opaguard.domain.PolicyBenchmark;

import java.nio.file.Path;
import java.util.Map;

/**
 * JSON-serializable snapshot of a policy benchmark used in Kafka events.
 *
 * @author Shelton Bumhe
 */
public record PolicyBenchmarkSnapshot(
        String label,
        String policyPath,
        BenchmarkMetrics metrics,
        Map<String, JsonNode> decisions) {
    public static PolicyBenchmarkSnapshot from(PolicyBenchmark benchmark) {
        return new PolicyBenchmarkSnapshot(benchmark.label(), benchmark.policyPath().toString(),
                benchmark.metrics(), benchmark.decisions());
    }

    public PolicyBenchmark toDomain() {
        return new PolicyBenchmark(label, Path.of(policyPath), metrics, decisions);
    }
}
