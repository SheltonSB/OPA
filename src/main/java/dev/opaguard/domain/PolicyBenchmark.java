package dev.opaguard.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.Map;

public record PolicyBenchmark(
        String label,
        Path policyPath,
        BenchmarkMetrics metrics,
        Map<String, JsonNode> decisions) {
}
