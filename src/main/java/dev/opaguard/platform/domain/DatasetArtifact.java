package dev.opaguard.platform.domain;

import java.util.UUID;

/**
 * Metadata identifying an immutable, content-addressed benchmark dataset.
 *
 * @param versionId dataset version identifier
 * @param organizationId owning tenant
 * @param objectKey content-addressed storage key
 * @param sha256 expected file digest
 * @param caseCount declared benchmark case count
 * @author Shelton Bumhe
 */
public record DatasetArtifact(UUID versionId, UUID organizationId, String objectKey, String sha256, long caseCount) {}
