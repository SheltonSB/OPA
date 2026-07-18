package dev.opaguard.platform.analyzer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opaguard.analysis.RegressionAnalyzer;
import dev.opaguard.domain.GuardReport;
import dev.opaguard.exception.GuardException;
import dev.opaguard.platform.domain.BenchmarkExecutionCompleted;
import dev.opaguard.platform.domain.BenchmarkJobCompleted;
import dev.opaguard.platform.domain.JobStatus;
import dev.opaguard.platform.messaging.KafkaTopics;
import dev.opaguard.platform.port.BenchmarkJobRepository;
import dev.opaguard.platform.port.BenchmarkReportRepository;
import dev.opaguard.platform.port.OutboxRepository;
import dev.opaguard.report.HtmlReportWriter;
import dev.opaguard.report.MarkdownReportWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/**
 * Produces terminal job state, report projections, and completion events.
 *
 * <p>Primary and optional historical comparisons are analyzed before a single
 * transaction updates the aggregate, stores reports, and appends the outbox event.</p>
 *
 * @author Shelton Bumhe
 */
@Service
@ConditionalOnProperty(name = "opa-guard.mode", havingValue = "analyzer")
public class RegressionAnalysisService {
    private final RegressionAnalyzer analyzer;
    private final MarkdownReportWriter markdown;
    private final HtmlReportWriter html;
    private final BenchmarkJobRepository jobs;
    private final BenchmarkReportRepository reports;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RegressionAnalysisService(RegressionAnalyzer analyzer, MarkdownReportWriter markdown, HtmlReportWriter html,
                                     BenchmarkJobRepository jobs, BenchmarkReportRepository reports,
                                     OutboxRepository outbox, ObjectMapper objectMapper,
                                     TransactionTemplate transactions, Clock clock) {
        this.analyzer = analyzer; this.markdown = markdown; this.html = html; this.jobs = jobs;
        this.reports = reports; this.outbox = outbox; this.objectMapper = objectMapper;
        this.transactions = transactions; this.clock = clock;
    }

    /**
     * Analyzes one completed execution idempotently.
     *
     * @param execution worker result containing all benchmark snapshots
     */
    public void analyze(BenchmarkExecutionCompleted execution) {
        var current = jobs.findById(execution.organizationId(), execution.jobId()).orElseThrow();
        if (current.status().terminal()) return;
        GuardReport primary = analyzer.analyze(execution.baseline().toDomain(), execution.candidate().toDomain(), execution.thresholds());
        GuardReport historical = execution.historical() == null ? null
                : analyzer.analyze(execution.historical().toDomain(), execution.candidate().toDomain(), execution.thresholds());
        boolean passed = primary.passed() && (historical == null || historical.passed());
        String renderedMarkdown = markdown.render(primary);
        if (historical != null) {
            renderedMarkdown += "\n\n---\n\n### Historical baseline comparison\n\n" + markdown.render(historical);
        }
        final String markdownReport = renderedMarkdown;
        var envelope = new AnalysisEnvelope(primary, historical, execution.baselineComplexity(), execution.candidateComplexity());
        String reportJson = json(envelope);
        String htmlReport = html.render(primary);
        var completed = new BenchmarkJobCompleted(
                UUID.randomUUID(), execution.jobId(), execution.organizationId(), passed ? "PASS" : "FAIL",
                execution.fingerprint(), json(primary), historical == null ? null : json(historical),
                execution.baselineComplexity(), execution.candidateComplexity(), clock.instant(), 1);
        String completedJson = json(completed);

        transactions.executeWithoutResult(ignored -> {
            var job = jobs.findById(execution.organizationId(), execution.jobId()).orElseThrow();
            if (job.status().terminal()) return;
            job.transitionTo(passed ? JobStatus.PASSED : JobStatus.FAILED, clock.instant());
            jobs.update(job);
            reports.save(execution.organizationId(), execution.jobId(), completed.status(), markdownReport,
                    htmlReport, reportJson, clock.instant());
            outbox.append(new OutboxRepository.OutboxMessage(
                    completed.eventId(), completed.jobId(), completed.organizationId(), KafkaTopics.ANALYSIS_COMPLETED_V1,
                    BenchmarkJobCompleted.class.getSimpleName(), completed.organizationId() + ":" + completed.jobId(),
                    completedJson, clock.instant(), 0));
        });
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new GuardException("Unable to serialize regression analysis", exception); }
    }

    /**
     * Machine-readable report containing both comparison axes and complexity data.
     *
     * @param primary protected-branch versus candidate comparison
     * @param historical optional historical versus candidate comparison
     * @param baselineComplexity baseline source complexity
     * @param candidateComplexity candidate source complexity
     * @author Shelton Bumhe
     */
    public record AnalysisEnvelope(
            GuardReport primary,
            GuardReport historical,
            BenchmarkJobCompleted.RegoComplexity baselineComplexity,
            BenchmarkJobCompleted.RegoComplexity candidateComplexity) {}
}
