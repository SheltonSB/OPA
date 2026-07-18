package dev.opaguard.platform.domain;

/**
 * Transport-neutral metric set retained by the distributed results plane.
 *
 * @author Shelton Bumhe
 */
public record BenchmarkResultMetrics(
        double averageLatencyNanos,
        double p95LatencyNanos,
        double p99LatencyNanos,
        double p999LatencyNanos,
        double throughputPerSecond,
        double cpuUtilizationPercent,
        long peakMemoryBytes,
        double allocationBytesPerSecond,
        double gcPauseMillis,
        double scalabilitySlope,
        long evaluationCount) {
}
