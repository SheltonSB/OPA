package dev.opaguard.benchmark;

import com.fasterxml.jackson.databind.node.BooleanNode;
import dev.opaguard.domain.BenchmarkCase;
import dev.opaguard.domain.Measurement;
import dev.opaguard.opa.PolicyEvaluator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkRunnerTest {

    @Test
    void calculatesMetricsAndExcludesWarmups() {
        AtomicInteger calls = new AtomicInteger();
        PolicyEvaluator evaluator = (policy, query, input, timeout) -> {
            int call = calls.incrementAndGet();
            long latency = call <= 2 ? 100_000_000L : call * 1_000_000L;
            return new Measurement(latency, 2_000_000L, 10_000L + call, BooleanNode.TRUE);
        };
        BenchmarkRunner runner = new BenchmarkRunner(evaluator);

        var result = runner.run(
                "main", Path.of("policy"), "data.authz.allow",
                List.of(new BenchmarkCase("one", BooleanNode.TRUE)),
                2, 3, Duration.ofSeconds(1));

        assertThat(calls).hasValue(5);
        assertThat(result.metrics().sampleCount()).isEqualTo(3);
        assertThat(result.metrics().averageLatencyMillis()).isEqualTo(4.0);
        assertThat(result.metrics().p95LatencyMillis()).isEqualTo(5.0);
        assertThat(result.metrics().throughputPerSecond()).isEqualTo(250.0);
        assertThat(result.decisions()).containsEntry("one", BooleanNode.TRUE);
    }

    @Test
    void alternatesBaselineAndCandidateOrderInPairedRuns() {
        Path baseline = Path.of("baseline");
        Path candidate = Path.of("candidate");
        List<Path> executionOrder = new ArrayList<>();
        PolicyEvaluator evaluator = (policy, query, input, timeout) -> {
            executionOrder.add(policy);
            return new Measurement(1_000_000L, 500_000L, 1_024L, BooleanNode.TRUE);
        };

        BenchmarkRunner.BenchmarkPair pair = new BenchmarkRunner(evaluator).runPaired(
                baseline, candidate, "data.authz.allow",
                List.of(new BenchmarkCase("one", BooleanNode.TRUE)),
                0, 2, Duration.ofSeconds(1));

        assertThat(executionOrder).containsExactly(baseline, candidate, candidate, baseline);
        assertThat(pair.baseline().metrics().sampleCount()).isEqualTo(2);
        assertThat(pair.candidate().metrics().sampleCount()).isEqualTo(2);
        assertThat(pair.baseline().decisions()).isEqualTo(pair.candidate().decisions());
    }
}
