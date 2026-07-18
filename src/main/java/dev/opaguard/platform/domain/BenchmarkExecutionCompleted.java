package dev.opaguard.platform.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned event carrying immutable worker measurements to the regression analyzer.
 *
 * @author Shelton Bumhe
 */
public record BenchmarkExecutionCompleted(
        UUID eventId,
        UUID jobId,
        UUID organizationId,
        String fingerprint,
        PolicyBenchmarkSnapshot baseline,
        PolicyBenchmarkSnapshot candidate,
        PolicyBenchmarkSnapshot historical,
        BenchmarkThresholds thresholds,
        BenchmarkJobCompleted.RegoComplexity baselineComplexity,
        BenchmarkJobCompleted.RegoComplexity candidateComplexity,
        Instant occurredAt,
        int schemaVersion) {
}
