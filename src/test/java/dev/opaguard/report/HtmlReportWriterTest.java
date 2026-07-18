package dev.opaguard.report;

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

class HtmlReportWriterTest {
    @Test
    void encodesUntrustedAnalysisText() {
        var metrics = new BenchmarkMetrics(1, 1, 1, 1, 1, 100, 1);
        var benchmark = new PolicyBenchmark("candidate", Path.of("policy"), metrics, Map.of("x", BooleanNode.TRUE));
        var report = new GuardReport("FAIL", Instant.EPOCH, benchmark, benchmark,
                List.of(new MetricComparison("<img src=x onerror=alert(1)>", 1, 2, 100, 10, true)),
                List.of(), "<script>alert(1)</script>", "use keyed objects");

        String html = new HtmlReportWriter().render(report);

        assertThat(html).doesNotContain("<script>").doesNotContain("<img src=x");
        assertThat(html).contains("&lt;script&gt;").contains("&lt;img src=x");
    }
}
