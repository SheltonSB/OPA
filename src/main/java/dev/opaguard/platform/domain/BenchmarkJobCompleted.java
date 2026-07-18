package dev.opaguard.platform.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned completion event consumed by CI status and pull-request integrations.
 *
 * @author Shelton Bumhe
 */
public record BenchmarkJobCompleted(
        UUID eventId,
        UUID jobId,
        UUID organizationId,
        String status,
        String fingerprint,
        String baselineReportJson,
        String historicalReportJson,
        RegoComplexity baselineComplexity,
        RegoComplexity candidateComplexity,
        Instant occurredAt,
        int schemaVersion) {

    /**
     * Static Rego complexity measurements carried with completion events.
     *
     * @param files Rego file count
     * @param lines source line count
     * @param rules estimated rule count
     * @param traversals estimated traversal count
     * @param comprehensions estimated comprehension count
     * @param score weighted complexity indicator
     * @author Shelton Bumhe
     */
    public record RegoComplexity(long files, long lines, long rules, long traversals, long comprehensions, long score) {}
}
