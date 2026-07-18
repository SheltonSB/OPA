package dev.opaguard.platform.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opaguard.analysis.RegressionAnalyzer;
import dev.opaguard.platform.domain.BenchmarkExecutionCompleted;
import dev.opaguard.platform.domain.BenchmarkJob;
import dev.opaguard.platform.domain.BenchmarkThresholds;
import dev.opaguard.platform.domain.JobStatus;
import dev.opaguard.platform.port.BenchmarkJobRepository;
import dev.opaguard.platform.port.BenchmarkReportRepository;
import dev.opaguard.platform.port.OutboxRepository;
import dev.opaguard.report.HtmlReportWriter;
import dev.opaguard.report.MarkdownReportWriter;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Ensures duplicate worker completion events cannot rewrite a terminal report.
 *
 * <p>The analyzer uses the benchmark aggregate as the durable idempotency key.
 * A redelivered event therefore performs one read and exits before invoking
 * report rendering, persistence, or another outbox publication.</p>
 *
 * @author Shelton Bumhe
 */
class RegressionAnalysisServiceIdempotencyTest {

    @Test
    void terminalJobSkipsDuplicateCompletionEvent() {
        BenchmarkJobRepository jobs = mock(BenchmarkJobRepository.class);
        RegressionAnalyzer analyzer = mock(RegressionAnalyzer.class);
        MarkdownReportWriter markdown = mock(MarkdownReportWriter.class);
        HtmlReportWriter html = mock(HtmlReportWriter.class);
        BenchmarkReportRepository reports = mock(BenchmarkReportRepository.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);

        UUID organization = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        when(jobs.findById(eq(organization), eq(jobId))).thenReturn(Optional.of(terminalJob(jobId, organization)));

        var service = new RegressionAnalysisService(analyzer, markdown, html, jobs, reports, outbox,
                new ObjectMapper(), transactions, Clock.systemUTC());

        service.analyze(new BenchmarkExecutionCompleted(
                UUID.randomUUID(), jobId, organization, "f".repeat(64), null, null, null,
                thresholds(), null, null, Instant.now(), 1));

        verify(jobs).findById(organization, jobId);
        verifyNoInteractions(analyzer, markdown, html, reports, outbox, transactions);
    }

    private BenchmarkJob terminalJob(UUID jobId, UUID organization) {
        return BenchmarkJob.builder().id(jobId).organizationId(organization)
                .baselineVersionId(UUID.randomUUID()).candidateVersionId(UUID.randomUUID())
                .datasetVersionId(UUID.randomUUID()).idempotencyKey("duplicate-completion")
                .thresholds(thresholds()).warmupIterations(1).measuredIterations(1)
                .status(JobStatus.PASSED).createdAt(Instant.now()).build();
    }

    private BenchmarkThresholds thresholds() {
        return new BenchmarkThresholds(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, true);
    }
}
