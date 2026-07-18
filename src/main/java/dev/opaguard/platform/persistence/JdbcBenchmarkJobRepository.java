package dev.opaguard.platform.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opaguard.exception.GuardException;
import dev.opaguard.platform.domain.BenchmarkJob;
import dev.opaguard.platform.domain.BenchmarkThresholds;
import dev.opaguard.platform.domain.ExecutionClaim;
import dev.opaguard.platform.domain.JobStatus;
import dev.opaguard.platform.port.BenchmarkJobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL benchmark aggregate adapter with idempotent creation and optimistic locking.
 *
 * @author Shelton Bumhe
 */
@Repository
@ConditionalOnExpression("'${opa-guard.mode:cli}' == 'coordinator' or '${opa-guard.mode:cli}' == 'worker' or '${opa-guard.mode:cli}' == 'analyzer'")
@Transactional
public class JdbcBenchmarkJobRepository implements BenchmarkJobRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcBenchmarkJobRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public CreationResult createIfAbsent(BenchmarkJob job) {
        setTenant(job.organizationId());
        int inserted = jdbc.sql("""
                INSERT INTO benchmark_jobs
                  (id, organization_id, baseline_version_id, candidate_version_id, historical_version_id,
                   dataset_version_id, idempotency_key, thresholds, warmup_iterations, measured_iterations,
                   status, created_at, updated_at, version)
                VALUES (:id, :org, :baseline, :candidate, :historical, :dataset, :key,
                        CAST(:thresholds AS jsonb), :warmup, :measured, :status, :created, :updated, :version)
                ON CONFLICT (organization_id, idempotency_key) DO NOTHING
                """)
                .param("id", job.id()).param("org", job.organizationId())
                .param("baseline", job.baselineVersionId()).param("candidate", job.candidateVersionId())
                .param("historical", job.historicalVersionId()).param("dataset", job.datasetVersionId())
                .param("key", job.idempotencyKey()).param("thresholds", json(job.thresholds()))
                .param("warmup", job.warmupIterations()).param("measured", job.measuredIterations())
                .param("status", job.status().name()).param("created", Timestamp.from(job.createdAt()))
                .param("updated", Timestamp.from(job.updatedAt())).param("version", job.version())
                .update();
        BenchmarkJob persisted = inserted == 1 ? job : findByIdempotencyKey(job.organizationId(), job.idempotencyKey()).orElseThrow();
        return new CreationResult(persisted, inserted == 1);
    }

    @Override
    public Optional<BenchmarkJob> findById(UUID organizationId, UUID jobId) {
        setTenant(organizationId);
        return jdbc.sql("SELECT * FROM benchmark_jobs WHERE organization_id=:org AND id=:id")
                .param("org", organizationId).param("id", jobId)
                .query(this::map).optional();
    }

    @Override
    public ExecutionClaim claimForExecution(UUID organizationId, UUID jobId, String workerId,
                                            Instant now, Instant leaseExpiresAt) {
        setTenant(organizationId);
        boolean claimed = jdbc.sql("""
                UPDATE benchmark_jobs
                SET status='RUNNING', updated_at=:now, version=version+1,
                    lease_owner=:worker, lease_expires_at=:expires
                WHERE organization_id=:org AND id=:id
                  AND (status='QUEUED' OR
                       (status='RUNNING' AND (lease_expires_at IS NULL OR lease_expires_at <= :now)))
                RETURNING id
                """).param("now", Timestamp.from(now)).param("worker", workerId)
                .param("expires", Timestamp.from(leaseExpiresAt))
                .param("org", organizationId).param("id", jobId)
                .query(UUID.class).optional().isPresent();
        if (claimed) return ExecutionClaim.CLAIMED;
        JobStatus status = jdbc.sql("""
                SELECT status FROM benchmark_jobs WHERE organization_id=:org AND id=:id
                """).param("org", organizationId).param("id", jobId)
                .query(String.class).optional().map(JobStatus::valueOf)
                .orElseThrow(() -> new GuardException("Benchmark job is unavailable"));
        return status == JobStatus.RUNNING ? ExecutionClaim.LEASED : ExecutionClaim.COMPLETE;
    }

    @Override
    public boolean renewExecutionLease(UUID organizationId, UUID jobId, String workerId, Instant leaseExpiresAt) {
        setTenant(organizationId);
        return jdbc.sql("""
                UPDATE benchmark_jobs SET lease_expires_at=:expires
                WHERE organization_id=:org AND id=:id AND status='RUNNING' AND lease_owner=:worker
                """).param("expires", Timestamp.from(leaseExpiresAt)).param("org", organizationId)
                .param("id", jobId).param("worker", workerId).update() == 1;
    }

    @Override
    public void releaseExecutionLease(UUID organizationId, UUID jobId, String workerId) {
        setTenant(organizationId);
        jdbc.sql("""
                UPDATE benchmark_jobs SET lease_owner=NULL, lease_expires_at=NULL
                WHERE organization_id=:org AND id=:id AND status='RUNNING' AND lease_owner=:worker
                """).param("org", organizationId).param("id", jobId).param("worker", workerId).update();
    }

    private Optional<BenchmarkJob> findByIdempotencyKey(UUID organizationId, String key) {
        return jdbc.sql("SELECT * FROM benchmark_jobs WHERE organization_id=:org AND idempotency_key=:key")
                .param("org", organizationId).param("key", key).query(this::map).optional();
    }

    @Override
    public void update(BenchmarkJob job) {
        setTenant(job.organizationId());
        int updated = jdbc.sql("""
                UPDATE benchmark_jobs SET status=:status, updated_at=:updated, version=:next_version,
                    lease_owner=CASE WHEN :status='RUNNING' THEN lease_owner ELSE NULL END,
                    lease_expires_at=CASE WHEN :status='RUNNING' THEN lease_expires_at ELSE NULL END
                WHERE organization_id=:org AND id=:id AND version=:expected_version
                """)
                .param("status", job.status().name()).param("updated", Timestamp.from(job.updatedAt()))
                .param("next_version", job.version()).param("expected_version", job.version() - 1)
                .param("org", job.organizationId()).param("id", job.id()).update();
        if (updated != 1) {
            throw new GuardException("Benchmark job was concurrently modified: " + job.id());
        }
    }

    private BenchmarkJob map(ResultSet rs, int row) throws SQLException {
        try {
            return BenchmarkJob.builder()
                    .id(rs.getObject("id", UUID.class)).organizationId(rs.getObject("organization_id", UUID.class))
                    .baselineVersionId(rs.getObject("baseline_version_id", UUID.class))
                    .candidateVersionId(rs.getObject("candidate_version_id", UUID.class))
                    .historicalVersionId(rs.getObject("historical_version_id", UUID.class))
                    .datasetVersionId(rs.getObject("dataset_version_id", UUID.class))
                    .idempotencyKey(rs.getString("idempotency_key"))
                    .thresholds(objectMapper.readValue(rs.getString("thresholds"), BenchmarkThresholds.class))
                    .warmupIterations(rs.getInt("warmup_iterations")).measuredIterations(rs.getInt("measured_iterations"))
                    .status(JobStatus.valueOf(rs.getString("status"))).createdAt(rs.getTimestamp("created_at").toInstant())
                    .updatedAt(rs.getTimestamp("updated_at").toInstant()).version(rs.getLong("version")).build();
        } catch (JsonProcessingException exception) {
            throw new SQLException("Invalid persisted threshold JSON", exception);
        }
    }

    private void setTenant(UUID organizationId) {
        jdbc.sql("SELECT set_config('app.tenant_id', :tenant, true)")
                .param("tenant", organizationId.toString()).query(String.class).single();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new GuardException("Unable to serialize benchmark configuration", exception);
        }
    }
}
