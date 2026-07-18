package dev.opaguard.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import dev.opaguard.domain.BenchmarkMetrics;
import dev.opaguard.domain.GuardReport;
import dev.opaguard.domain.MetricComparison;
import dev.opaguard.domain.PolicyBenchmark;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownReportWriterTest {
    @Test
    void rendersPullRequestFriendlySummary() {
        var metrics = new BenchmarkMetrics(2.3, 3, 4, 434.78, 1.2, 10_485_760, 30);
        var benchmark = new PolicyBenchmark("main", Path.of("policy"), metrics, Map.of("case", BooleanNode.TRUE));
        var report = new GuardReport(
                "PASS", Instant.parse("2026-01-01T00:00:00Z"), benchmark, benchmark,
                List.of(new MetricComparison("Average latency (ms)", 2.3, 2.5, 8.7, 10, false)),
                List.of(), "None", "No action required.");

        String markdown = new MarkdownReportWriter(new ObjectMapper()).render(report);

        assertThat(markdown).contains("OPA Policy Performance Guard: PASS");
        assertThat(markdown).contains("+8.70%");
        assertThat(markdown).contains("Decision correctness:** PASS");
    }
}
