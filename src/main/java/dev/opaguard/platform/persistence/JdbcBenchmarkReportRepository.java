package dev.opaguard.platform.persistence;

import dev.opaguard.platform.port.BenchmarkReportRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL adapter for tenant-scoped multi-format report projections.
 *
 * @author Shelton Bumhe
 */
@Repository
@Transactional
@ConditionalOnExpression("'${opa-guard.mode:cli}' == 'coordinator' or '${opa-guard.mode:cli}' == 'analyzer'")
public class JdbcBenchmarkReportRepository implements BenchmarkReportRepository {
    private final JdbcClient jdbc;
    public JdbcBenchmarkReportRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public void save(UUID organizationId, UUID jobId, String status, String markdown,
                     String html, String reportJson, Instant createdAt) {
        jdbc.sql("SELECT set_config('app.tenant_id', :tenant, true)")
                .param("tenant", organizationId.toString()).query(String.class).single();
        jdbc.sql("""
                INSERT INTO benchmark_reports (organization_id, job_id, status, markdown, html, report_json, created_at)
                VALUES (:org, :job, :status, :markdown, :html, CAST(:json AS jsonb), :created)
                ON CONFLICT (organization_id, job_id) DO UPDATE SET
                  status=excluded.status, markdown=excluded.markdown, html=excluded.html,
                  report_json=excluded.report_json, created_at=excluded.created_at
                """).param("org", organizationId).param("job", jobId).param("status", status)
                .param("markdown", markdown).param("html", html).param("json", reportJson)
                .param("created", Timestamp.from(createdAt)).update();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredReport> find(UUID organizationId, UUID jobId) {
        jdbc.sql("SELECT set_config('app.tenant_id', :tenant, true)")
                .param("tenant", organizationId.toString()).query(String.class).single();
        return jdbc.sql("""
                SELECT status, markdown, html, report_json::text, created_at FROM benchmark_reports
                WHERE organization_id=:org AND job_id=:job
                """).param("org", organizationId).param("job", jobId)
                .query((rs, row) -> new StoredReport(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getTimestamp(5).toInstant())).optional();
    }
}
