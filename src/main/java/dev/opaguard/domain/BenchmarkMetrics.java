package dev.opaguard.domain;

/**
 * Aggregated performance statistics for one immutable policy version.
 *
 * <p>Latency fields are expressed in milliseconds. A zero allocation or GC value means the
 * selected evaluator could not expose that runtime metric.</p>
 *
 * @param averageLatencyMillis arithmetic mean wall-clock latency
 * @param p95LatencyMillis 95th percentile wall-clock latency
 * @param p99LatencyMillis 99th percentile wall-clock latency
 * @param p999LatencyMillis 99.9th percentile wall-clock latency
 * @param throughputPerSecond completed evaluations per second
 * @param averageCpuMillis arithmetic mean process CPU time
 * @param cpuUtilizationPercent CPU time divided by wall-clock time
 * @param peakMemoryBytes highest observed resident-set size
 * @param allocationRateBytesPerSecond OPA allocation rate, when available
 * @param gcPauseMillis cumulative OPA garbage-collection pause, when available
 * @param scalabilitySlope log-log throughput scaling slope
 * @param sampleCount measured evaluation count
 * @author Shelton Bumhe
 */
public record BenchmarkMetrics(
        double averageLatencyMillis,
        double p95LatencyMillis,
        double p99LatencyMillis,
        double p999LatencyMillis,
        double throughputPerSecond,
        double averageCpuMillis,
        double cpuUtilizationPercent,
        long peakMemoryBytes,
        double allocationRateBytesPerSecond,
        double gcPauseMillis,
        double scalabilitySlope,
        int sampleCount) {

    public BenchmarkMetrics(double averageLatencyMillis, double p95LatencyMillis, double p99LatencyMillis,
                            double throughputPerSecond, double averageCpuMillis, long peakMemoryBytes, int sampleCount) {
        this(averageLatencyMillis, p95LatencyMillis, p99LatencyMillis, p99LatencyMillis,
                throughputPerSecond, averageCpuMillis,
                averageLatencyMillis == 0 ? 0 : averageCpuMillis / averageLatencyMillis * 100,
                peakMemoryBytes, 0, 0, 0, sampleCount);
    }

    public BenchmarkMetrics withScalabilitySlope(double slope) {
        return new BenchmarkMetrics(averageLatencyMillis, p95LatencyMillis, p99LatencyMillis, p999LatencyMillis,
                throughputPerSecond, averageCpuMillis, cpuUtilizationPercent, peakMemoryBytes,
                allocationRateBytesPerSecond, gcPauseMillis, slope, sampleCount);
    }
}
