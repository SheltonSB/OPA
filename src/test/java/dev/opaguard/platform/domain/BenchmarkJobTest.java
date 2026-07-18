package dev.opaguard.platform.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkJobTest {
    @Test
    void enforcesStateMachineAndOptimisticVersion() {
        BenchmarkJob job = job();

        job.transitionTo(JobStatus.RUNNING, Instant.parse("2026-01-01T00:00:01Z"));
        job.transitionTo(JobStatus.ANALYZING, Instant.parse("2026-01-01T00:00:02Z"));
        job.transitionTo(JobStatus.PASSED, Instant.parse("2026-01-01T00:00:03Z"));

        assertThat(job.status()).isEqualTo(JobStatus.PASSED);
        assertThat(job.version()).isEqualTo(3);
        assertThatThrownBy(() -> job.transitionTo(JobStatus.RUNNING, Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUnboundedIterations() {
        assertThatThrownBy(() -> baseBuilder().measuredIterations(1_000_001).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("measuredIterations");
    }

    private BenchmarkJob job() { return baseBuilder().build(); }

    private BenchmarkJob.Builder baseBuilder() {
        return BenchmarkJob.builder().id(UUID.randomUUID()).organizationId(UUID.randomUUID())
                .baselineVersionId(UUID.randomUUID()).candidateVersionId(UUID.randomUUID())
                .datasetVersionId(UUID.randomUUID()).idempotencyKey("request-1")
                .thresholds(new BenchmarkThresholds(
                        BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                        BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, true))
                .warmupIterations(5).measuredIterations(30).createdAt(Instant.parse("2026-01-01T00:00:00Z"));
    }
}
