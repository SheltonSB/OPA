package dev.opaguard.domain;

public record MetricComparison(
        String metric,
        double baseline,
        double candidate,
        double regressionPercent,
        double thresholdPercent,
        boolean thresholdExceeded) {
}
