package dev.opaguard.cli;

import com.fasterxml.jackson.databind.node.BooleanNode;
import dev.opaguard.domain.BenchmarkMetrics;
import dev.opaguard.domain.DecisionMismatch;
import dev.opaguard.domain.GuardReport;
import dev.opaguard.domain.MetricComparison;
import dev.opaguard.domain.PolicyBenchmark;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies stable mapping from benchmark reports to CLI exit codes.
 *
 * @author Shelton Bumhe
 */
class DeveloperCommandServiceTest {
    @Test
    void decisionMismatchUsesCodeTenWhenEnabled() {
        GuardReport report = report(List.of(), List.of(new DecisionMismatch("case", BooleanNode.FALSE, BooleanNode.TRUE)));

        assertThat(DeveloperCommandService.guardExitCode(report, true))
                .isEqualTo(CliExitCode.DECISION_MISMATCH);
        assertThat(DeveloperCommandService.guardExitCode(report, false))
                .isEqualTo(CliExitCode.PASS);
    }

    @Test
    void latencyAndMemoryFailuresUseSpecificAndCombinedCodes() {
        GuardReport latency = report(List.of(new MetricComparison("p95 latency (ms)", 1, 2, 100, 10, true)), List.of());
        GuardReport memory = report(List.of(new MetricComparison("Peak memory (bytes)", 1, 2, 100, 10, true)), List.of());
        GuardReport both = report(List.of(
                new MetricComparison("p95 latency (ms)", 1, 2, 100, 10, true),
                new MetricComparison("Peak memory (bytes)", 1, 2, 100, 10, true)), List.of());

        assertThat(DeveloperCommandService.guardExitCode(latency, true)).isEqualTo(CliExitCode.LATENCY_REGRESSION);
        assertThat(DeveloperCommandService.guardExitCode(memory, true)).isEqualTo(CliExitCode.MEMORY_REGRESSION);
        assertThat(DeveloperCommandService.guardExitCode(both, true)).isEqualTo(CliExitCode.MULTIPLE_GUARD_FAILURES);
    }

    private GuardReport report(List<MetricComparison> comparisons, List<DecisionMismatch> mismatches) {
        PolicyBenchmark benchmark = new PolicyBenchmark("test", Path.of("policy"),
                new BenchmarkMetrics(1, 1, 1, 1, 1, 1, 1), Map.of("case", BooleanNode.FALSE));
        return new GuardReport("FAIL", Instant.EPOCH, benchmark, benchmark, comparisons, mismatches, "cause", "recommendation");
    }
}
