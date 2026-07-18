package dev.opaguard.report;

import dev.opaguard.domain.GuardReport;
import dev.opaguard.domain.MetricComparison;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Renders a standalone HTML report with context-appropriate output encoding.
 *
 * @author Shelton Bumhe
 */
@Component
public class HtmlReportWriter {
    /**
     * Renders a report as a responsive HTML document.
     *
     * @param report report to render
     * @return encoded standalone HTML
     */
    public String render(GuardReport report) {
        StringBuilder rows = new StringBuilder();
        for (MetricComparison metric : report.comparisons()) {
            rows.append("<tr><th>").append(escape(metric.metric())).append("</th><td>")
                    .append(number(metric.baseline())).append("</td><td>").append(number(metric.candidate()))
                    .append("</td><td>").append(number(metric.regressionPercent())).append("%</td><td>")
                    .append(number(metric.thresholdPercent())).append("%</td><td class=\"")
                    .append(metric.thresholdExceeded() ? "fail\">FAIL" : "pass\">PASS").append("</td></tr>");
        }
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>OPA Policy Performance Guard</title>
                <style>body{font:16px system-ui;max-width:1100px;margin:2rem auto;padding:0 1rem;color:#17202a}
                table{border-collapse:collapse;width:100%%}th,td{padding:.6rem;border:1px solid #ccd1d1;text-align:right}
                th:first-child{text-align:left}.pass{color:#18794e;font-weight:700}.fail{color:#b42318;font-weight:700}</style></head>
                <body><h1>OPA Policy Performance Guard: %s</h1><table><thead><tr><th>Metric</th><th>Main</th>
                <th>Candidate</th><th>Regression</th><th>Threshold</th><th>Status</th></tr></thead><tbody>%s</tbody></table>
                <h2>Analysis</h2><p><strong>Detected cause:</strong> %s</p><p><strong>Recommendation:</strong> %s</p>
                <p>Generated at <time>%s</time></p></body></html>
                """.formatted(escape(report.status()), rows, escape(report.detectedCause()),
                escape(report.recommendation()), escape(report.generatedAt().toString()));
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "unbounded";
    }

    static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
