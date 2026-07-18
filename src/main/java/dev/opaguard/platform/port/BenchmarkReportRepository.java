package dev.opaguard.platform.port;

import java.time.Instant;
import java.util.UUID;
import java.util.Optional;

/**
 * Persistence port for immutable report projections.
 *
 * @author Shelton Bumhe
 */
public interface BenchmarkReportRepository {
    /**
     * Stores or replaces the report projection for a job.
     *
     * @param organizationId owning tenant
     * @param jobId benchmark job identifier
     * @param status terminal guard status
     * @param markdown pull-request report
     * @param html standalone HTML report
     * @param reportJson machine-readable report
     * @param createdAt projection timestamp
     */
    void save(UUID organizationId, UUID jobId, String status, String markdown, String html, String reportJson, Instant createdAt);

    /**
     * Finds a report within a tenant boundary.
     *
     * @param organizationId owning tenant
     * @param jobId benchmark job identifier
     * @return report projection, if available
     */
    Optional<StoredReport> find(UUID organizationId, UUID jobId);

    /**
     * Materialized report formats returned by the query API.
     *
     * @param status terminal guard status
     * @param markdown Markdown representation
     * @param html HTML representation
     * @param reportJson JSON representation
     * @param createdAt projection timestamp
     * @author Shelton Bumhe
     */
    record StoredReport(String status, String markdown, String html, String reportJson, Instant createdAt) {}
}
