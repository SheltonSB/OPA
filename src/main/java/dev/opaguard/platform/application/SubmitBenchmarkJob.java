package dev.opaguard.platform.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opaguard.exception.GuardException;
import dev.opaguard.platform.domain.BenchmarkJob;
import dev.opaguard.platform.domain.BenchmarkJobRequested;
import dev.opaguard.platform.domain.BenchmarkThresholds;
import dev.opaguard.platform.messaging.KafkaTopics;
import dev.opaguard.platform.port.BenchmarkJobRepository;
import dev.opaguard.platform.port.OutboxRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Creates benchmark jobs and their request events as one transactional use case.
 *
 * <p>Tenant-scoped idempotency prevents duplicate CI retries from scheduling
 * duplicate work. The transactional outbox closes the database-to-Kafka gap.</p>
 *
 * @author Shelton Bumhe
 */
@Service
@ConditionalOnProperty(name = "opa-guard.mode", havingValue = "coordinator")
public class SubmitBenchmarkJob {
    private final BenchmarkJobRepository jobs;
    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SubmitBenchmarkJob(BenchmarkJobRepository jobs, OutboxRepository outbox, ObjectMapper objectMapper, Clock clock) {
        this.jobs = jobs;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Submits a job or returns the existing job for the same idempotency key.
     *
     * @param command validated job parameters
     * @return effective benchmark job
     */
    @Transactional
    public BenchmarkJob submit(Command command) {
        var now = clock.instant();
        var job = BenchmarkJob.builder()
                .id(UUID.randomUUID()).organizationId(command.organizationId())
                .baselineVersionId(command.baselineVersionId()).candidateVersionId(command.candidateVersionId())
                .historicalVersionId(command.historicalVersionId()).datasetVersionId(command.datasetVersionId())
                .idempotencyKey(command.idempotencyKey()).thresholds(command.thresholds())
                .warmupIterations(command.warmupIterations()).measuredIterations(command.measuredIterations())
                .createdAt(now).build();
        var creation = jobs.createIfAbsent(job);
        if (creation.created()) {
            var event = new BenchmarkJobRequested(
                    UUID.randomUUID(), job.id(), job.organizationId(), job.baselineVersionId(), job.candidateVersionId(),
                    job.historicalVersionId(), job.datasetVersionId(), job.thresholds(), job.warmupIterations(),
                    job.measuredIterations(), now, 1);
            outbox.append(new OutboxRepository.OutboxMessage(
                    event.eventId(), job.id(), job.organizationId(), KafkaTopics.JOB_REQUESTED_V1,
                    BenchmarkJobRequested.class.getSimpleName(), job.organizationId() + ":" + job.id(),
                    toJson(event), now, 0));
        }
        return creation.job();
    }

    private String toJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException exception) {
            throw new GuardException("Unable to serialize job event", exception);
        }
    }

    /**
     * Complete input to the benchmark submission use case.
     *
     * @param organizationId owning tenant
     * @param baselineVersionId protected-branch policy version
     * @param candidateVersionId proposed policy version
     * @param historicalVersionId optional historical baseline
     * @param datasetVersionId immutable dataset version
     * @param idempotencyKey caller-provided retry key
     * @param thresholds regression gates
     * @param warmupIterations unmeasured iterations
     * @param measuredIterations measured iterations
     * @author Shelton Bumhe
     */
    public record Command(
            UUID organizationId, UUID baselineVersionId, UUID candidateVersionId, UUID historicalVersionId,
            UUID datasetVersionId, String idempotencyKey, BenchmarkThresholds thresholds,
            int warmupIterations, int measuredIterations) {}
}
