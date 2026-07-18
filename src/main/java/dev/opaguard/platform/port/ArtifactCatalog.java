package dev.opaguard.platform.port;

import dev.opaguard.platform.domain.DatasetArtifact;
import dev.opaguard.platform.domain.PolicyArtifact;

import java.util.UUID;

/**
 * Tenant-scoped read port for immutable policy and dataset metadata.
 *
 * @author Shelton Bumhe
 */
public interface ArtifactCatalog {
    /**
     * Finds a policy version owned by an organization.
     *
     * @param organizationId tenant boundary
     * @param versionId policy version identifier
     * @return policy metadata
     */
    PolicyArtifact policy(UUID organizationId, UUID versionId);

    /**
     * Finds a dataset version owned by an organization.
     *
     * @param organizationId tenant boundary
     * @param versionId dataset version identifier
     * @return dataset metadata
     */
    DatasetArtifact dataset(UUID organizationId, UUID versionId);
}
