package dev.opaguard.analysis;

import dev.opaguard.domain.BenchmarkMetrics;
import dev.opaguard.domain.DecisionMismatch;
import dev.opaguard.domain.GuardReport;
import dev.opaguard.domain.MetricComparison;
import dev.opaguard.domain.PolicyBenchmark;
import dev.opaguard.platform.domain.BenchmarkThresholds;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Compares benchmark measurements and authorization decisions against policy gates.
 *
 * <p>The analyzer is side-effect free apart from obtaining the report timestamp,
 * which makes comparison behavior deterministic under an injected {@link Clock}.</p>
 *
 * @author Shelton Bumhe
 */
@Service
public class RegressionAnalyzer {
    private final Clock clock;
    private final PolicyChangeAdvisor advisor;

    /**
     * Creates an analyzer with an explicit time source and policy advisor.
     *
     * @param clock source used to timestamp reports
     * @param advisor component that explains failed comparisons
     */
    public RegressionAnalyzer(Clock clock, PolicyChangeAdvisor advisor) {
        this.clock = clock;
        this.advisor = advisor;
    }

    /**
     * Analyzes a comparison using the compact CLI threshold model.
     *
     * @param baseline benchmark produced from the protected branch
     * @param candidate benchmark produced from the proposed change
     * @param latencyThreshold maximum percentage increase for latency percentiles
     * @param memoryThreshold maximum percentage increase for peak memory
     * @param failOnDecisionChange whether decision differences fail the report
     * @return immutable pass/fail report
     */
    public GuardReport analyze(
            PolicyBenchmark baseline,
            PolicyBenchmark candidate,
            double latencyThreshold,
            double memoryThreshold,
            boolean failOnDecisionChange) {

        BenchmarkMetrics main = baseline.metrics();
        BenchmarkMetrics pullRequest = candidate.metrics();
        List<MetricComparison> comparisons = List.of(
                compare("Average latency (ms)", main.averageLatencyMillis(), pullRequest.averageLatencyMillis(), latencyThreshold),
                compare("p95 latency (ms)", main.p95LatencyMillis(), pullRequest.p95LatencyMillis(), latencyThreshold),
                compare("p99 latency (ms)", main.p99LatencyMillis(), pullRequest.p99LatencyMillis(), latencyThreshold),
                compare("p999 latency (ms)", main.p999LatencyMillis(), pullRequest.p999LatencyMillis(), latencyThreshold),
                compare("Peak memory (bytes)", main.peakMemoryBytes(), pullRequest.peakMemoryBytes(), memoryThreshold));

        List<DecisionMismatch> mismatches = new ArrayList<>();
        baseline.decisions().forEach((caseId, baselineDecision) -> {
            var candidateDecision = candidate.decisions().get(caseId);
            if (candidateDecision == null || !baselineDecision.equals(candidateDecision)) {
                mismatches.add(new DecisionMismatch(caseId, baselineDecision, candidateDecision));
            }
        });

        boolean regression = comparisons.stream().anyMatch(MetricComparison::thresholdExceeded);
        boolean correctnessFailure = failOnDecisionChange && !mismatches.isEmpty();
        boolean passed = !regression && !correctnessFailure;
        PolicyChangeAdvisor.Advice advice = passed
                ? PolicyChangeAdvisor.Advice.none()
                : advisor.advise(baseline.policyPath(), candidate.policyPath(), mismatches, regression);

        return new GuardReport(
                passed ? "PASS" : "FAIL",
                clock.instant(),
                baseline,
                candidate,
                comparisons,
                List.copyOf(mismatches),
                advice.cause(),
                advice.recommendation());
    }

    /**
     * Analyzes a comparison using the distributed platform threshold model.
     *
     * @param baseline benchmark produced from the protected branch
     * @param candidate benchmark produced from the proposed change
     * @param thresholds all configured performance and correctness gates
     * @return immutable pass/fail report
     */
    public GuardReport analyze(PolicyBenchmark baseline, PolicyBenchmark candidate, BenchmarkThresholds thresholds) {
        BenchmarkMetrics main = baseline.metrics();
        BenchmarkMetrics pullRequest = candidate.metrics();
        List<MetricComparison> comparisons = List.of(
                compare("Average latency (ms)", main.averageLatencyMillis(), pullRequest.averageLatencyMillis(),
                        thresholds.maximumAverageLatencyRegressionPercent().doubleValue()),
                compare("p95 latency (ms)", main.p95LatencyMillis(), pullRequest.p95LatencyMillis(),
                        thresholds.maximumP95LatencyRegressionPercent().doubleValue()),
                compare("p99 latency (ms)", main.p99LatencyMillis(), pullRequest.p99LatencyMillis(),
                        thresholds.maximumP99LatencyRegressionPercent().doubleValue()),
                compare("p999 latency (ms)", main.p999LatencyMillis(), pullRequest.p999LatencyMillis(),
                        thresholds.maximumP999LatencyRegressionPercent().doubleValue()),
                compare("Peak memory (bytes)", main.peakMemoryBytes(), pullRequest.peakMemoryBytes(),
                        thresholds.maximumMemoryRegressionPercent().doubleValue()),
                compare("Allocation rate (bytes/s)", main.allocationRateBytesPerSecond(),
                        pullRequest.allocationRateBytesPerSecond(),
                        thresholds.maximumAllocationRateRegressionPercent().doubleValue()),
                compareHigherIsBetter("Scalability slope", main.scalabilitySlope(), pullRequest.scalabilitySlope(),
                        thresholds.maximumScalabilitySlopeRegressionPercent().doubleValue()));
        List<DecisionMismatch> mismatches = decisionMismatches(baseline, candidate);
        boolean regression = comparisons.stream().anyMatch(MetricComparison::thresholdExceeded);
        boolean correctnessFailure = thresholds.failOnDecisionChange() && !mismatches.isEmpty();
        boolean passed = !regression && !correctnessFailure;
        PolicyChangeAdvisor.Advice advice = passed ? PolicyChangeAdvisor.Advice.none()
                : advisor.advise(baseline.policyPath(), candidate.policyPath(), mismatches, regression);
        return new GuardReport(passed ? "PASS" : "FAIL", clock.instant(), baseline, candidate,
                comparisons, mismatches, advice.cause(), advice.recommendation());
    }

    private static List<DecisionMismatch> decisionMismatches(PolicyBenchmark baseline, PolicyBenchmark candidate) {
        List<DecisionMismatch> mismatches = new ArrayList<>();
        baseline.decisions().forEach((caseId, baselineDecision) -> {
            var candidateDecision = candidate.decisions().get(caseId);
            if (candidateDecision == null || !baselineDecision.equals(candidateDecision)) {
                mismatches.add(new DecisionMismatch(caseId, baselineDecision, candidateDecision));
            }
        });
        return List.copyOf(mismatches);
    }

    static MetricComparison compare(String name, double baseline, double candidate, double threshold) {
        double regression = regressionPercent(baseline, candidate);
        return new MetricComparison(name, baseline, candidate, regression, threshold, regression - threshold > 1e-9);
    }

    static double regressionPercent(double baseline, double candidate) {
        if (baseline == 0) {
            return candidate == 0 ? 0 : Double.POSITIVE_INFINITY;
        }
        return ((candidate - baseline) / baseline) * 100d;
    }

    static MetricComparison compareHigherIsBetter(String name, double baseline, double candidate, double threshold) {
        double regression = baseline == 0 ? (candidate < 0 ? Double.POSITIVE_INFINITY : 0)
                : ((baseline - candidate) / Math.abs(baseline)) * 100d;
        return new MetricComparison(name, baseline, candidate, regression, threshold, regression - threshold > 1e-9);
    }
}
