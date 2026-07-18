package dev.opaguard.platform.port;

import dev.opaguard.platform.domain.DatasetArtifact;
import dev.opaguard.platform.domain.PolicyArtifact;

import java.time.Instant;
import java.util.UUID;

/**
 * Write port for registering immutable, content-addressed artifact versions.
 *
 * @author Shelton Bumhe
 */
public interface ArtifactRegistrationRepository {
    /**
     * Registers policy metadata after its content has been safely stored.
     *
     * @param organizationId owning tenant
     * @param versionId policy version identifier
     * @param repository source repository name
     * @param gitCommit source commit identifier
     * @param objectKey artifact-store key
     * @param sha256 expected lowercase SHA-256 digest
     * @param query fully qualified OPA decision query
     * @param createdAt registration timestamp
     * @return registered policy metadata
     */
    PolicyArtifact registerPolicy(UUID organizationId, UUID versionId, String repository, String gitCommit,
                                  String objectKey, String sha256, String query, Instant createdAt);

    /**
     * Registers dataset metadata after its content has been safely stored.
     *
     * @param organizationId owning tenant
     * @param versionId dataset version identifier
     * @param objectKey artifact-store key
     * @param sha256 expected lowercase SHA-256 digest
     * @param caseCount number of benchmark cases
     * @param sizeBytes serialized dataset size
     * @param createdAt registration timestamp
     * @return registered dataset metadata
     */
    DatasetArtifact registerDataset(UUID organizationId, UUID versionId, String objectKey, String sha256,
                                    long caseCount, long sizeBytes, Instant createdAt);
}
