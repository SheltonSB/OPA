package dev.opaguard.benchmark;

import dev.opaguard.domain.BenchmarkCase;
import dev.opaguard.opa.PolicyEvaluator;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Measures how OPA throughput changes as evaluation concurrency increases.
 *
 * <p>Virtual-thread tasks drive fixed concurrency levels while a log-log
 * regression slope summarizes scaling efficiency.</p>
 *
 * @author Shelton Bumhe
 */
public class ScalabilityBenchmarkRunner {
    private static final List<Integer> CONCURRENCY_LEVELS = List.of(1, 2, 4, 8);
    private final PolicyEvaluator evaluator;

    /**
     * Creates a scalability runner.
     *
     * @param evaluator policy engine adapter to exercise
     */
    public ScalabilityBenchmarkRunner(PolicyEvaluator evaluator) { this.evaluator = evaluator; }

    /**
     * Measures throughput at each supported concurrency level.
     *
     * @param policyPath policy bundle file or directory
     * @param query fully qualified OPA data query
     * @param cases non-empty benchmark dataset
     * @param measuredIterations requested iteration count used to size the sample
     * @param timeout per-evaluation deadline
     * @return immutable throughput points and scaling slope
     */
    public ScalabilityProfile run(Path policyPath, String query, List<BenchmarkCase> cases,
                                  int measuredIterations, Duration timeout) {
        if (cases.isEmpty()) throw new IllegalArgumentException("Scalability benchmark requires cases");
        long requested = Math.multiplyExact((long) cases.size(), Math.min(measuredIterations, 10));
        int samplesPerLevel = (int) Math.min(1_000, Math.max(100, requested));
        List<Point> points = new ArrayList<>();
        for (int concurrency : CONCURRENCY_LEVELS) {
            long started = System.nanoTime();
            try (ExecutorService executor = Executors.newFixedThreadPool(concurrency, Thread.ofVirtual().factory())) {
                List<Future<?>> futures = new ArrayList<>(samplesPerLevel);
                for (int sample = 0; sample < samplesPerLevel; sample++) {
                    BenchmarkCase benchmarkCase = cases.get(sample % cases.size());
                    futures.add(executor.submit(() -> evaluator.evaluate(policyPath, query, benchmarkCase.input(), timeout)));
                }
                for (Future<?> future : futures) future.get();
            } catch (Exception exception) {
                throw new IllegalStateException("Scalability benchmark failed at concurrency " + concurrency, exception);
            }
            double seconds = (System.nanoTime() - started) / 1_000_000_000d;
            points.add(new Point(concurrency, samplesPerLevel / seconds));
        }
        return new ScalabilityProfile(List.copyOf(points), logLogSlope(points));
    }

    static double logLogSlope(List<Point> points) {
        double meanX = points.stream().mapToDouble(point -> Math.log(point.concurrency())).average().orElseThrow();
        double meanY = points.stream().mapToDouble(point -> Math.log(point.throughputPerSecond())).average().orElseThrow();
        double numerator = 0, denominator = 0;
        for (Point point : points) {
            double x = Math.log(point.concurrency()) - meanX;
            numerator += x * (Math.log(point.throughputPerSecond()) - meanY);
            denominator += x * x;
        }
        return denominator == 0 ? 0 : numerator / denominator;
    }

    /**
     * Throughput observed at one concurrency level.
     *
     * @param concurrency simultaneous evaluations
     * @param throughputPerSecond completed evaluations per second
     * @author Shelton Bumhe
     */
    public record Point(int concurrency, double throughputPerSecond) {}

    /**
     * Throughput curve and its log-log scaling slope.
     *
     * @param points ordered concurrency measurements
     * @param slope scaling efficiency; values near one indicate linear scaling
     * @author Shelton Bumhe
     */
    public record ScalabilityProfile(List<Point> points, double slope) {}
}
