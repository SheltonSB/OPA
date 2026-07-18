package dev.opaguard.platform.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned integration event requesting distributed execution of a benchmark job.
 *
 * @author Shelton Bumhe
 */
public record BenchmarkJobRequested(
        UUID eventId,
        UUID jobId,
        UUID organizationId,
        UUID baselineVersionId,
        UUID candidateVersionId,
        UUID historicalVersionId,
        UUID datasetVersionId,
        BenchmarkThresholds thresholds,
        int warmupIterations,
        int measuredIterations,
        Instant occurredAt,
        int schemaVersion) {
}
