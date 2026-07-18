package dev.opaguard.domain;

import java.time.Instant;
import java.util.List;

public record GuardReport(
        String status,
        Instant generatedAt,
        PolicyBenchmark baseline,
        PolicyBenchmark candidate,
        List<MetricComparison> comparisons,
        List<DecisionMismatch> decisionMismatches,
        String detectedCause,
        String recommendation) {

    public boolean passed() {
        return "PASS".equals(status);
    }
}
