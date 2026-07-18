package dev.opaguard.platform.domain;

import java.util.UUID;

/**
 * Metadata identifying an immutable, content-addressed policy version.
 *
 * @param versionId policy version identifier
 * @param organizationId owning tenant
 * @param objectKey content-addressed storage key
 * @param sha256 expected policy tree digest
 * @param query fully qualified OPA decision query
 * @author Shelton Bumhe
 */
public record PolicyArtifact(UUID versionId, UUID organizationId, String objectKey, String sha256, String query) {}
