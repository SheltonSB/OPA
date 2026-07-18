package dev.opaguard.platform.persistence;

import dev.opaguard.platform.domain.DatasetArtifact;
import dev.opaguard.platform.domain.PolicyArtifact;
import dev.opaguard.platform.port.ArtifactRegistrationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * Idempotent PostgreSQL adapter for policy and dataset version registration.
 *
 * @author Shelton Bumhe
 */
@Repository
@Transactional
@ConditionalOnProperty(name = "opa-guard.mode", havingValue = "coordinator")
public class JdbcArtifactRegistrationRepository implements ArtifactRegistrationRepository {
    private final JdbcClient jdbc;
    public JdbcArtifactRegistrationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public PolicyArtifact registerPolicy(UUID organizationId, UUID versionId, String repository, String gitCommit,
                                         String objectKey, String sha256, String query, Instant createdAt) {
        setTenant(organizationId);
        int inserted = jdbc.sql("""
                INSERT INTO policy_versions
                  (id, organization_id, repository, git_commit, object_key, sha256, query_path, created_at)
                VALUES (:id, :org, :repository, :commit, :key, :sha, :query, :created)
                ON CONFLICT (organization_id, repository, git_commit, sha256) DO NOTHING
                """).param("id", versionId).param("org", organizationId).param("repository", repository)
                .param("commit", gitCommit).param("key", objectKey).param("sha", sha256)
                .param("query", query).param("created", Timestamp.from(createdAt)).update();
        if (inserted == 0) {
            return jdbc.sql("""
                    SELECT id, organization_id, object_key, sha256, query_path FROM policy_versions
                    WHERE organization_id=:org AND repository=:repository AND git_commit=:commit AND sha256=:sha
                    """).param("org", organizationId).param("repository", repository).param("commit", gitCommit)
                    .param("sha", sha256).query((rs, row) -> new PolicyArtifact(rs.getObject(1, UUID.class),
                            rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5))).single();
        }
        return new PolicyArtifact(versionId, organizationId, objectKey, sha256, query);
    }

    @Override
    public DatasetArtifact registerDataset(UUID organizationId, UUID versionId, String objectKey, String sha256,
                                           long caseCount, long sizeBytes, Instant createdAt) {
        setTenant(organizationId);
        int inserted = jdbc.sql("""
                INSERT INTO dataset_versions
                  (id, organization_id, object_key, sha256, case_count, size_bytes, created_at)
                VALUES (:id, :org, :key, :sha, :cases, :size, :created)
                ON CONFLICT (organization_id, sha256) DO NOTHING
                """).param("id", versionId).param("org", organizationId).param("key", objectKey)
                .param("sha", sha256).param("cases", caseCount).param("size", sizeBytes)
                .param("created", Timestamp.from(createdAt)).update();
        if (inserted == 0) {
            return jdbc.sql("""
                    SELECT id, organization_id, object_key, sha256, case_count FROM dataset_versions
                    WHERE organization_id=:org AND sha256=:sha
                    """).param("org", organizationId).param("sha", sha256)
                    .query((rs, row) -> new DatasetArtifact(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getString(3), rs.getString(4), rs.getLong(5))).single();
        }
        return new DatasetArtifact(versionId, organizationId, objectKey, sha256, caseCount);
    }

    private void setTenant(UUID organizationId) {
        jdbc.sql("SELECT set_config('app.tenant_id', :tenant, true)")
                .param("tenant", organizationId.toString()).query(String.class).single();
    }
}
