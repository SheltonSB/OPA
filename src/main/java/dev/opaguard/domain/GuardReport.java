package dev.opaguard.domain;

import java.time.Instant;
import java.util.List;

/**
 * Final correctness and performance verdict rendered by report adapters and CI integrations.
 *
 * @param status {@code PASS} or {@code FAIL}
 * @param generatedAt report creation timestamp
 * @param baseline protected-branch benchmark
 * @param candidate proposed-change benchmark
 * @param comparisons metric-by-metric regression results
 * @param decisionMismatches authorization correctness differences
 * @param detectedCause human-readable diagnosis
 * @param recommendation suggested remediation
 * @author Shelton Bumhe
 */
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
