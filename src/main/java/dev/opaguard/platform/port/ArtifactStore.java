package dev.opaguard.platform.port;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Port for resolving verified artifacts into worker-local read-only paths.
 *
 * @author Shelton Bumhe
 */
public interface ArtifactStore {
    /**
     * Resolves and integrity-checks a policy artifact.
     *
     * @param organizationId owning tenant
     * @param objectKey opaque catalog-issued key
     * @param expectedSha256 expected content digest
     * @return local policy path
     */
    Path resolvePolicy(UUID organizationId, String objectKey, String expectedSha256);

    /**
     * Resolves and integrity-checks a dataset artifact.
     *
     * @param organizationId owning tenant
     * @param objectKey opaque catalog-issued key
     * @param expectedSha256 expected content digest
     * @return local dataset path
     */
    Path resolveDataset(UUID organizationId, String objectKey, String expectedSha256);
}
