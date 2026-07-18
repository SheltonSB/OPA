package dev.opaguard.platform.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opaguard.benchmark.BenchmarkRunner;
import dev.opaguard.benchmark.DatasetLoader;
import dev.opaguard.benchmark.ScalabilityBenchmarkRunner;
import dev.opaguard.platform.analysis.RegoComplexityAnalyzer;
import dev.opaguard.platform.domain.BenchmarkJobRequested;
import dev.opaguard.platform.domain.BenchmarkThresholds;
import dev.opaguard.platform.domain.ExecutionClaim;
import dev.opaguard.platform.port.ArtifactCatalog;
import dev.opaguard.platform.port.ArtifactStore;
import dev.opaguard.platform.port.BenchmarkJobRepository;
import dev.opaguard.platform.port.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies the durable execution-claim guard used by at-least-once Kafka delivery.
 *
 * <p>A duplicate event must be acknowledged by the listener without resolving
 * artifacts or invoking OPA a second time. This is the application-level
 * idempotency invariant; transport redelivery itself is covered by
 * {@code KafkaRedeliveryIntegrationTest}.</p>
 *
 * @author Shelton Bumhe
 */
class DistributedBenchmarkWorkerIdempotencyTest {

    @Test
    void completedDuplicateDoesNotRepeatBenchmarkOrPublishResult() {
        BenchmarkJobRepository jobs = mock(BenchmarkJobRepository.class);
        ArtifactCatalog catalog = mock(ArtifactCatalog.class);
        ArtifactStore artifactStore = mock(ArtifactStore.class);
        DatasetLoader datasets = mock(DatasetLoader.class);
        BenchmarkRunner runner = mock(BenchmarkRunner.class);
        RegoComplexityAnalyzer complexity = mock(RegoComplexityAnalyzer.class);
        ScalabilityBenchmarkRunner scalability = mock(ScalabilityBenchmarkRunner.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        IncrementalResultCache cache = mock(IncrementalResultCache.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);

        UUID organization = UUID.randomUUID();
        UUID job = UUID.randomUUID();
        when(jobs.claimForExecution(any(), any(), anyString(), any(), any())).thenReturn(ExecutionClaim.COMPLETE);

        var worker = new DistributedBenchmarkWorker(
                catalog, artifactStore, datasets, runner, complexity, scalability,
                jobs, outbox, cache, new ObjectMapper(), Clock.systemUTC(), transactions);

        worker.execute(new BenchmarkJobRequested(
                UUID.randomUUID(), job, organization, UUID.randomUUID(), UUID.randomUUID(),
                null, UUID.randomUUID(), thresholds(), 1, 10, Instant.now(), 1));

        verify(jobs).claimForExecution(any(), any(), anyString(), any(), any());
        verify(jobs, never()).renewExecutionLease(any(), any(), anyString(), any());
        verify(jobs, never()).releaseExecutionLease(any(), any(), anyString());
        verifyNoInteractions(catalog, artifactStore, datasets, runner, complexity, scalability, outbox, cache, transactions);
    }

    private BenchmarkThresholds thresholds() {
        return new BenchmarkThresholds(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, true);
    }
}
