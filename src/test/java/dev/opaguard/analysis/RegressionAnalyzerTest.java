package dev.opaguard.analysis;

import com.fasterxml.jackson.databind.node.BooleanNode;
import dev.opaguard.domain.BenchmarkMetrics;
import dev.opaguard.domain.PolicyBenchmark;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RegressionAnalyzerTest {
    private final RegressionAnalyzer analyzer = new RegressionAnalyzer(
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
            new PolicyChangeAdvisor());

    @Test
    void failsWhenAverageLatencyExceedsThreshold() {
        PolicyBenchmark baseline = benchmark(10, 100, true);
        PolicyBenchmark candidate = benchmark(12, 105, true);

        var report = analyzer.analyze(baseline, candidate, 10, 10, true);

        assertThat(report.status()).isEqualTo("FAIL");
        assertThat(report.comparisons().get(0).regressionPercent()).isEqualTo(20);
        assertThat(report.comparisons().get(0).thresholdExceeded()).isTrue();
    }

    @Test
    void failsWhenDecisionChangesEvenIfMetricsPass() {
        var report = analyzer.analyze(benchmark(10, 100, true), benchmark(10, 100, false), 10, 10, true);

        assertThat(report.status()).isEqualTo("FAIL");
        assertThat(report.decisionMismatches()).hasSize(1);
    }

    @Test
    void passesAtTheInclusiveThreshold() {
        var report = analyzer.analyze(benchmark(10, 100, true), benchmark(11, 110, true), 10, 10, true);

        assertThat(report.status()).isEqualTo("PASS");
    }

    @Test
    void treatsUndersizedTailPercentilesAsInformational() {
        PolicyBenchmark baseline = benchmarkWithTails(10, 10, 10, 100, 10);
        PolicyBenchmark candidate = benchmarkWithTails(10, 10, 100, 1_000, 10);

        var report = analyzer.analyze(baseline, candidate, 10, 10, true);

        assertThat(report.status()).isEqualTo("PASS");
        assertThat(report.comparisons().get(2).metric()).contains("informational");
        assertThat(report.comparisons().get(2).thresholdExceeded()).isFalse();
        assertThat(report.comparisons().get(3).thresholdExceeded()).isFalse();
    }

    @Test
    void enforcesP99WhenAtLeastOneHundredSamplesExist() {
        PolicyBenchmark baseline = benchmarkWithTails(10, 10, 10, 10, 100);
        PolicyBenchmark candidate = benchmarkWithTails(10, 10, 20, 20, 100);

        var report = analyzer.analyze(baseline, candidate, 10, 10, true);

        assertThat(report.status()).isEqualTo("FAIL");
        assertThat(report.comparisons().get(2).thresholdExceeded()).isTrue();
        assertThat(report.comparisons().get(3).metric()).contains("informational");
    }

    private PolicyBenchmark benchmark(double latency, long memory, boolean decision) {
        return new PolicyBenchmark(
                "test", Path.of("policy"),
                new BenchmarkMetrics(latency, latency, latency, 1000 / latency, 1, memory, 10),
                Map.of("case", BooleanNode.valueOf(decision)));
    }

    private PolicyBenchmark benchmarkWithTails(double average, double p95, double p99, double p999,
                                                int sampleCount) {
        return new PolicyBenchmark(
                "test", Path.of("policy"),
                new BenchmarkMetrics(average, p95, p99, p999, 1000 / average, 1, 10,
                        100, 0, 0, 0, sampleCount),
                Map.of("case", BooleanNode.TRUE));
    }
}
