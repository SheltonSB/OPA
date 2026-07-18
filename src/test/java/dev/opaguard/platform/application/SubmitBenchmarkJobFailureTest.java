package dev.opaguard.platform.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opaguard.platform.domain.BenchmarkThresholds;
import dev.opaguard.platform.port.BenchmarkJobRepository;
import dev.opaguard.platform.port.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SubmitBenchmarkJobFailureTest {
    @Test
    void databaseFailureDoesNotPublishAnOrphanedCommand() {
        BenchmarkJobRepository jobs = mock(BenchmarkJobRepository.class);
        OutboxRepository outbox = mock(OutboxRepository.class);
        when(jobs.createIfAbsent(any())).thenThrow(new DataAccessResourceFailureException("database unavailable"));
        var service = new SubmitBenchmarkJob(jobs, outbox, new ObjectMapper(), Clock.systemUTC());

        var thresholds = new BenchmarkThresholds(
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, true);
        var command = new SubmitBenchmarkJob.Command(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
                "database-failure", thresholds, 1, 10);

        assertThatThrownBy(() -> service.submit(command))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(outbox);
    }
}
