package dev.opaguard.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import dev.opaguard.domain.BenchmarkCase;
import dev.opaguard.domain.BenchmarkMetrics;
import dev.opaguard.domain.Measurement;
import dev.opaguard.domain.PolicyBenchmark;
import dev.opaguard.opa.PolicyEvaluator;
import dev.opaguard.opa.RuntimeTelemetryProvider;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes warmup and measured policy evaluations and aggregates their telemetry.
 *
 * <p>A runner is scoped to one {@link PolicyEvaluator}. It applies a hard sample
 * ceiling so a malformed or oversized job cannot exhaust a worker heap.</p>
 *
 * @author Shelton Bumhe
 */
public class BenchmarkRunner {
    static final int MAX_SAMPLES_PER_WORKER = 5_000_000;
    private final PolicyEvaluator evaluator;

    /**
     * Creates a benchmark runner.
     *
     * @param evaluator policy engine adapter used for every sample
     */
    public BenchmarkRunner(PolicyEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    /**
     * Executes an identical dataset against one policy version.
     *
     * @param label human-readable version label
     * @param policyPath policy bundle file or directory
     * @param query fully qualified OPA data query
     * @param cases validated benchmark cases
     * @param warmupIterations iterations excluded from reported metrics
     * @param measuredIterations iterations included in reported metrics
     * @param timeout per-evaluation deadline
     * @return benchmark metrics and first observed decision for each case
     * @throws ArithmeticException if the requested sample count overflows
     * @throws IllegalArgumentException if the worker sample limit is exceeded
     */
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

        long requestedSamples = Math.multiplyExact((long) cases.size(), measuredIterations);
        if (requestedSamples > MAX_SAMPLES_PER_WORKER) {
            throw new IllegalArgumentException("A worker may execute at most " + MAX_SAMPLES_PER_WORKER
                    + " measured samples; split this benchmark into shards");
        }
        RuntimeTelemetryProvider.RuntimeTelemetry before = evaluator instanceof RuntimeTelemetryProvider telemetry
                ? telemetry.snapshot(policyPath) : RuntimeTelemetryProvider.RuntimeTelemetry.unavailable();
        List<Measurement> measurements = new ArrayList<>((int) requestedSamples);
        Map<String, JsonNode> decisions = new LinkedHashMap<>();
        for (int iteration = 0; iteration < measuredIterations; iteration++) {
            for (BenchmarkCase benchmarkCase : cases) {
                Measurement measurement = evaluator.evaluate(policyPath, query, benchmarkCase.input(), timeout);
                measurements.add(measurement);
                decisions.putIfAbsent(benchmarkCase.id(), measurement.decision());
            }
        }

        RuntimeTelemetryProvider.RuntimeTelemetry after = evaluator instanceof RuntimeTelemetryProvider telemetry
                ? telemetry.snapshot(policyPath) : RuntimeTelemetryProvider.RuntimeTelemetry.unavailable();
        // Reports and events must not disclose worker-local absolute filesystem paths.
        return new PolicyBenchmark(label, policyPath.normalize(), metrics(measurements, before, after), Map.copyOf(decisions));
    }

    /**
     * Runs baseline and candidate samples in alternating order to reduce thermal and scheduler drift.
     *
     * <p>This is the preferred local CI comparison. Distributed workers still execute independent
     * policy versions because their scheduling order is balanced by the benchmark fingerprint.</p>
     *
     * @param baselinePath protected-branch policy
     * @param candidatePath proposed policy
     * @param query fully qualified OPA data query
     * @param cases identical ordered dataset
     * @param warmupIterations iterations excluded from metrics
     * @param measuredIterations iterations included in metrics
     * @param timeout per-evaluation deadline
     * @return paired policy benchmarks
     */
    public BenchmarkPair runPaired(Path baselinePath, Path candidatePath, String query,
                                   List<BenchmarkCase> cases, int warmupIterations,
                                   int measuredIterations, Duration timeout) {
        long requestedSamples = Math.multiplyExact((long) cases.size(), measuredIterations);
        if (requestedSamples > MAX_SAMPLES_PER_WORKER) {
            throw new IllegalArgumentException("A worker may execute at most " + MAX_SAMPLES_PER_WORKER
                    + " measured samples per policy; split this benchmark into shards");
        }
        for (int iteration = 0; iteration < warmupIterations; iteration++) {
            for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
                BenchmarkCase benchmarkCase = cases.get(caseIndex);
                if ((iteration + caseIndex) % 2 == 0) {
                    evaluator.evaluate(baselinePath, query, benchmarkCase.input(), timeout);
                    evaluator.evaluate(candidatePath, query, benchmarkCase.input(), timeout);
                } else {
                    evaluator.evaluate(candidatePath, query, benchmarkCase.input(), timeout);
                    evaluator.evaluate(baselinePath, query, benchmarkCase.input(), timeout);
                }
            }
        }

        RuntimeTelemetryProvider.RuntimeTelemetry baselineBefore = telemetry(baselinePath);
        RuntimeTelemetryProvider.RuntimeTelemetry candidateBefore = telemetry(candidatePath);
        List<Measurement> baselineSamples = new ArrayList<>((int) requestedSamples);
        List<Measurement> candidateSamples = new ArrayList<>((int) requestedSamples);
        Map<String, JsonNode> baselineDecisions = new LinkedHashMap<>();
        Map<String, JsonNode> candidateDecisions = new LinkedHashMap<>();
        for (int iteration = 0; iteration < measuredIterations; iteration++) {
            for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
                BenchmarkCase benchmarkCase = cases.get(caseIndex);
                boolean baselineFirst = (iteration + caseIndex) % 2 == 0;
                Measurement first = evaluator.evaluate(
                        baselineFirst ? baselinePath : candidatePath, query, benchmarkCase.input(), timeout);
                Measurement second = evaluator.evaluate(
                        baselineFirst ? candidatePath : baselinePath, query, benchmarkCase.input(), timeout);
                Measurement baseline = baselineFirst ? first : second;
                Measurement candidate = baselineFirst ? second : first;
                baselineSamples.add(baseline);
                candidateSamples.add(candidate);
                baselineDecisions.putIfAbsent(benchmarkCase.id(), baseline.decision());
                candidateDecisions.putIfAbsent(benchmarkCase.id(), candidate.decision());
            }
        }
        RuntimeTelemetryProvider.RuntimeTelemetry baselineAfter = telemetry(baselinePath);
        RuntimeTelemetryProvider.RuntimeTelemetry candidateAfter = telemetry(candidatePath);
        return new BenchmarkPair(
                new PolicyBenchmark("main", baselinePath.normalize(),
                        metrics(baselineSamples, baselineBefore, baselineAfter), Map.copyOf(baselineDecisions)),
                new PolicyBenchmark("pr", candidatePath.normalize(),
                        metrics(candidateSamples, candidateBefore, candidateAfter), Map.copyOf(candidateDecisions)));
    }

    private RuntimeTelemetryProvider.RuntimeTelemetry telemetry(Path policyPath) {
        return evaluator instanceof RuntimeTelemetryProvider provider
                ? provider.snapshot(policyPath) : RuntimeTelemetryProvider.RuntimeTelemetry.unavailable();
    }

    /**
     * Baseline and candidate measurements produced by an interleaved run.
     *
     * @param baseline protected-branch result
     * @param candidate proposed-change result
     * @author Shelton Bumhe
     */
    public record BenchmarkPair(PolicyBenchmark baseline, PolicyBenchmark candidate) {}

    static BenchmarkMetrics metrics(List<Measurement> samples) {
        return metrics(samples, RuntimeTelemetryProvider.RuntimeTelemetry.unavailable(),
                RuntimeTelemetryProvider.RuntimeTelemetry.unavailable());
    }

    static BenchmarkMetrics metrics(List<Measurement> samples, RuntimeTelemetryProvider.RuntimeTelemetry before,
                                    RuntimeTelemetryProvider.RuntimeTelemetry after) {
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
        double elapsedSeconds = samples.stream().mapToLong(Measurement::wallTimeNanos).sum() / 1_000_000_000d;
        long allocatedBytes = Math.max(0, after.allocatedBytes() - before.allocatedBytes());
        long gcPauseNanos = Math.max(0, after.gcPauseNanos() - before.gcPauseNanos());
        return new BenchmarkMetrics(
                nanosToMillis(averageNanos),
                nanosToMillis(percentile(sortedLatencies, 0.95)),
                nanosToMillis(percentile(sortedLatencies, 0.99)),
                nanosToMillis(percentile(sortedLatencies, 0.999)),
                1_000_000_000d / averageNanos,
                nanosToMillis(averageCpuNanos),
                averageNanos == 0 ? 0 : averageCpuNanos / averageNanos * 100d,
                peakMemory,
                elapsedSeconds == 0 ? 0 : allocatedBytes / elapsedSeconds,
                nanosToMillis(gcPauseNanos),
                0,
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
