package dev.opaguard.analysis;

import dev.opaguard.domain.BenchmarkMetrics;
import dev.opaguard.domain.DecisionMismatch;
import dev.opaguard.domain.GuardReport;
import dev.opaguard.domain.MetricComparison;
import dev.opaguard.domain.PolicyBenchmark;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

@Service
public class RegressionAnalyzer {
    private final Clock clock;
    private final PolicyChangeAdvisor advisor;

    public RegressionAnalyzer(Clock clock, PolicyChangeAdvisor advisor) {
        this.clock = clock;
        this.advisor = advisor;
    }

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
}
