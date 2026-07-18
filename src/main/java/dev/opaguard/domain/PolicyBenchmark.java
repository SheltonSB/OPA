package dev.opaguard.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.Map;

/**
 * Complete benchmark output for one policy tree, including metrics and decisions by case.
 *
 * @param label human-readable policy version label
 * @param policyPath verified local policy path
 * @param metrics aggregated performance measurements
 * @param decisions first observed decision indexed by benchmark case identifier
 * @author Shelton Bumhe
 */
public record PolicyBenchmark(
        String label,
        Path policyPath,
        BenchmarkMetrics metrics,
        Map<String, JsonNode> decisions) {
}
