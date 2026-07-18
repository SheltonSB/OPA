package dev.opaguard.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import dev.opaguard.domain.BenchmarkCase;
import dev.opaguard.domain.BenchmarkMetrics;
import dev.opaguard.domain.Measurement;
import dev.opaguard.domain.PolicyBenchmark;
import dev.opaguard.opa.PolicyEvaluator;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BenchmarkRunner {
    private final PolicyEvaluator evaluator;

    public BenchmarkRunner(PolicyEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    public PolicyBenchmark run(
            String label,
            Path policyPath,
            String query,
            List<BenchmarkCase> cases,
            int warmupIterations,
            int measuredIterations,
            Duration timeout) {

        for (int iteration = 0; iteration < warmupIterations; iteration++) {
            cases.forEach(benchmarkCase -> evaluator.evaluate(policyPath, query, benchmarkCase.input(), timeout));
        }

        List<Measurement> measurements = new ArrayList<>(cases.size() * measuredIterations);
        Map<String, JsonNode> decisions = new LinkedHashMap<>();
        for (int iteration = 0; iteration < measuredIterations; iteration++) {
            for (BenchmarkCase benchmarkCase : cases) {
                Measurement measurement = evaluator.evaluate(policyPath, query, benchmarkCase.input(), timeout);
                measurements.add(measurement);
                decisions.putIfAbsent(benchmarkCase.id(), measurement.decision());
            }
        }

        return new PolicyBenchmark(label, policyPath.toAbsolutePath().normalize(), metrics(measurements), Map.copyOf(decisions));
    }

    static BenchmarkMetrics metrics(List<Measurement> samples) {
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("At least one benchmark sample is required");
        }
        List<Long> sortedLatencies = samples.stream()
                .map(Measurement::wallTimeNanos)
                .sorted(Comparator.naturalOrder())
                .toList();
        double averageNanos = samples.stream().mapToLong(Measurement::wallTimeNanos).average().orElseThrow();
        double averageCpuNanos = samples.stream().mapToLong(Measurement::cpuTimeNanos).average().orElse(0);
        long peakMemory = samples.stream().mapToLong(Measurement::peakMemoryBytes).max().orElse(0);
        return new BenchmarkMetrics(
                nanosToMillis(averageNanos),
                nanosToMillis(percentile(sortedLatencies, 0.95)),
                nanosToMillis(percentile(sortedLatencies, 0.99)),
                1_000_000_000d / averageNanos,
                nanosToMillis(averageCpuNanos),
                peakMemory,
                samples.size());
    }

    private static long percentile(List<Long> sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
        return sorted.get(index);
    }

    private static double nanosToMillis(double nanos) {
        return nanos / 1_000_000d;
    }
}
