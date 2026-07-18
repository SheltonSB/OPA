package dev.opaguard.platform.domain;

import java.math.BigDecimal;

/**
 * Tenant-selected regression gates applied by the analyzer.
 *
 * <p>All percentage values must be in the inclusive range 0–10,000.</p>
 *
 * @author Shelton Bumhe
 */
public record BenchmarkThresholds(
        BigDecimal maximumAverageLatencyRegressionPercent,
        BigDecimal maximumP95LatencyRegressionPercent,
        BigDecimal maximumP99LatencyRegressionPercent,
        BigDecimal maximumP999LatencyRegressionPercent,
        BigDecimal maximumMemoryRegressionPercent,
        BigDecimal maximumAllocationRateRegressionPercent,
        BigDecimal maximumScalabilitySlopeRegressionPercent,
        boolean failOnDecisionChange) {

    public BenchmarkThresholds {
        requirePercentage(maximumAverageLatencyRegressionPercent, "average latency");
        requirePercentage(maximumP95LatencyRegressionPercent, "p95 latency");
        requirePercentage(maximumP99LatencyRegressionPercent, "p99 latency");
        requirePercentage(maximumP999LatencyRegressionPercent, "p999 latency");
        requirePercentage(maximumMemoryRegressionPercent, "memory");
        requirePercentage(maximumAllocationRateRegressionPercent, "allocation rate");
        requirePercentage(maximumScalabilitySlopeRegressionPercent, "scalability slope");
    }

    private static void requirePercentage(BigDecimal value, String name) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(10_000)) > 0) {
            throw new IllegalArgumentException(name + " threshold must be between 0 and 10000 percent");
        }
    }
}
