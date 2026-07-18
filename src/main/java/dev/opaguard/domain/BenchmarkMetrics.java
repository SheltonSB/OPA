package dev.opaguard.domain;

public record BenchmarkMetrics(
        double averageLatencyMillis,
        double p95LatencyMillis,
        double p99LatencyMillis,
        double throughputPerSecond,
        double averageCpuMillis,
        long peakMemoryBytes,
        int sampleCount) {
}
