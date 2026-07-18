package dev.opaguard.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opaguard.domain.BenchmarkMetrics;
import dev.opaguard.domain.DecisionMismatch;
import dev.opaguard.domain.GuardReport;
import dev.opaguard.domain.MetricComparison;
import dev.opaguard.exception.GuardException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Renders reports as GitHub-compatible Markdown with escaped untrusted values.
 *
 * @author Shelton Bumhe
 */
@Component
public class MarkdownReportWriter implements ReportWriter {
    private final ObjectMapper objectMapper;

    /**
     * Creates a Markdown writer.
     *
     * @param objectMapper mapper used to render decision values
     */
    public MarkdownReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void write(GuardReport report, Path output) {
        try {
            JsonReportWriter.createParent(output);
            Files.writeString(output, render(report));
        } catch (IOException exception) {
            throw new GuardException("Unable to write Markdown report to " + output, exception);
        }
    }

    /**
     * Renders a self-contained pull-request summary.
     *
     * @param report report to render
     * @return GitHub-flavored Markdown
     */
    public String render(GuardReport report) {
        String icon = report.passed() ? "✅" : "❌";
        StringBuilder markdown = new StringBuilder()
                .append("## ").append(icon).append(" OPA Policy Performance Guard: ").append(report.status()).append("\n\n")
                .append("| Metric | Main | PR | Regression | Threshold | Status |\n")
                .append("|---|---:|---:|---:|---:|:---:|\n");

        for (MetricComparison comparison : report.comparisons()) {
            markdown.append("| ").append(comparison.metric())
                    .append(" | ").append(value(comparison.metric(), comparison.baseline()))
                    .append(" | ").append(value(comparison.metric(), comparison.candidate()))
                    .append(" | ").append(percent(comparison.regressionPercent()))
                    .append(" | ").append(format(comparison.thresholdPercent())).append("%")
                    .append(" | ").append(comparison.thresholdExceeded() ? "FAIL" : "PASS").append(" |\n");
        }

        BenchmarkMetrics main = report.baseline().metrics();
        BenchmarkMetrics candidate = report.candidate().metrics();
        markdown.append("\n### Additional metrics\n\n")
                .append("| Branch | Throughput (ops/s) | Avg CPU (ms) | CPU utilization | Allocation rate | GC pause | Samples |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|\n")
                .append("| Main | ").append(format(main.throughputPerSecond())).append(" | ")
                .append(format(main.averageCpuMillis())).append(" | ").append(format(main.cpuUtilizationPercent())).append("% | ")
                .append(format(main.allocationRateBytesPerSecond())).append(" B/s | ").append(format(main.gcPauseMillis())).append(" ms | ")
                .append(main.sampleCount()).append(" |\n")
                .append("| PR | ").append(format(candidate.throughputPerSecond())).append(" | ")
                .append(format(candidate.averageCpuMillis())).append(" | ").append(format(candidate.cpuUtilizationPercent())).append("% | ")
                .append(format(candidate.allocationRateBytesPerSecond())).append(" B/s | ").append(format(candidate.gcPauseMillis())).append(" ms | ")
                .append(candidate.sampleCount()).append(" |\n");

        if (report.decisionMismatches().isEmpty()) {
            markdown.append("\n**Decision correctness:** PASS — all benchmark decisions are identical.\n");
        } else {
            markdown.append("\n### Decision mismatches\n\n")
                    .append("| Case | Main | PR |\n|---|---|---|\n");
            for (DecisionMismatch mismatch : report.decisionMismatches()) {
                markdown.append("| ").append(escape(mismatch.caseId()))
                        .append(" | `").append(json(mismatch.baseline())).append("`")
                        .append(" | `").append(json(mismatch.candidate())).append("` |\n");
            }
        }

        if (!report.passed()) {
            markdown.append("\n### Analysis\n\n")
                    .append("**Detected cause:** ").append(report.detectedCause()).append("\n\n")
                    .append("**Recommendation:** ").append(report.recommendation()).append("\n");
        }
        markdown.append("\n<sub>Generated at ").append(report.generatedAt()).append("</sub>\n");
        return markdown.toString();
    }

    private String json(Object value) {
        try {
            return escape(objectMapper.writeValueAsString(value)).replace("`", "\\`");
        } catch (JsonProcessingException exception) {
            return "unavailable";
        }
    }

    private static String value(String metric, double value) {
        if (metric.contains("bytes/s")) {
            return format(value / (1024d * 1024d)) + " MiB/s";
        }
        if (metric.contains("bytes")) {
            return format(value / (1024d * 1024d)) + " MiB";
        }
        if (metric.contains("slope")) {
            return format(value);
        }
        return format(value) + " ms";
    }

    private static String percent(double value) {
        if (Double.isInfinite(value)) {
            return "+∞%";
        }
        return (value >= 0 ? "+" : "") + format(value) + "%";
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String escape(String text) {
        return text == null ? "null" : text.replace("|", "\\|").replace("\n", " ");
    }
}
