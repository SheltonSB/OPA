package dev.opaguard.platform.api;

import dev.opaguard.platform.domain.BenchmarkJob;
import dev.opaguard.platform.domain.JobStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Public benchmark job status representation.
 *
 * @param id job identifier
 * @param organizationId owning tenant
 * @param status current lifecycle state
 * @param baselineVersionId protected-branch policy version
 * @param candidateVersionId proposed policy version
 * @param historicalVersionId optional historical policy version
 * @param datasetVersionId benchmark dataset version
 * @param createdAt creation timestamp
 * @param updatedAt most recent state transition timestamp
 * @author Shelton Bumhe
 */
public record BenchmarkJobResponse(
        UUID id,
        UUID organizationId,
        JobStatus status,
        UUID baselineVersionId,
        UUID candidateVersionId,
        UUID historicalVersionId,
        UUID datasetVersionId,
        Instant createdAt,
        Instant updatedAt) {
    static BenchmarkJobResponse from(BenchmarkJob job) {
        return new BenchmarkJobResponse(job.id(), job.organizationId(), job.status(), job.baselineVersionId(),
                job.candidateVersionId(), job.historicalVersionId(), job.datasetVersionId(),
                job.createdAt(), job.updatedAt());
    }
}
