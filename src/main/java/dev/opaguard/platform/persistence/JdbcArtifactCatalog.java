package dev.opaguard.platform.persistence;

import dev.opaguard.exception.GuardException;
import dev.opaguard.platform.domain.DatasetArtifact;
import dev.opaguard.platform.domain.PolicyArtifact;
import dev.opaguard.platform.port.ArtifactCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * PostgreSQL artifact catalog adapter with transaction-local tenant context.
 *
 * @author Shelton Bumhe
 */
@Repository
@Transactional(readOnly = true)
@ConditionalOnProperty(name = "opa-guard.mode", havingValue = "worker")
public class JdbcArtifactCatalog implements ArtifactCatalog {
    private final JdbcClient jdbc;

    public JdbcArtifactCatalog(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public PolicyArtifact policy(UUID organizationId, UUID versionId) {
        setTenant(organizationId);
        return jdbc.sql("""
                SELECT id, organization_id, object_key, sha256, query_path FROM policy_versions
                WHERE organization_id=:org AND id=:id
                """).param("org", organizationId).param("id", versionId)
                .query((rs, row) -> new PolicyArtifact(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5))).optional()
                .orElseThrow(() -> new GuardException("Policy version is unavailable"));
    }

    @Override
    public DatasetArtifact dataset(UUID organizationId, UUID versionId) {
        setTenant(organizationId);
        return jdbc.sql("""
                SELECT id, organization_id, object_key, sha256, case_count FROM dataset_versions
                WHERE organization_id=:org AND id=:id
                """).param("org", organizationId).param("id", versionId)
                .query((rs, row) -> new DatasetArtifact(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getLong(5))).optional()
                .orElseThrow(() -> new GuardException("Dataset version is unavailable"));
    }

    private void setTenant(UUID organizationId) {
        jdbc.sql("SELECT set_config('app.tenant_id', :tenant, true)")
                .param("tenant", organizationId.toString()).query(String.class).single();
    }
}
