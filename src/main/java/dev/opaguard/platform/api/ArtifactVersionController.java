package dev.opaguard.platform.api;

import dev.opaguard.platform.port.ArtifactRegistrationRepository;
import dev.opaguard.platform.security.RedisRateLimiter;
import dev.opaguard.platform.security.TenantAuthorizer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Clock;
import java.util.UUID;

/**
 * Registers immutable policy and dataset versions for an authenticated tenant.
 *
 * <p>Registration stores metadata only; artifact content must already exist at
 * its content-addressed key and is reverified by workers before use.</p>
 *
 * @author Shelton Bumhe
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}")
@ConditionalOnProperty(name = "opa-guard.mode", havingValue = "coordinator")
public class ArtifactVersionController {
    private final ArtifactRegistrationRepository repository;
    private final TenantAuthorizer authorizer;
    private final RedisRateLimiter rateLimiter;
    private final Clock clock;

    public ArtifactVersionController(ArtifactRegistrationRepository repository, TenantAuthorizer authorizer,
                                     RedisRateLimiter rateLimiter, Clock clock) {
        this.repository = repository; this.authorizer = authorizer; this.rateLimiter = rateLimiter; this.clock = clock;
    }

    /**
     * Registers one policy version.
     *
     * @param organizationId tenant path identifier
     * @param request validated policy metadata
     * @param authentication authenticated JWT principal
     * @return HTTP 201 with registered metadata
     */
    @PostMapping("/policy-versions")
    public ResponseEntity<PolicyVersionResponse> registerPolicy(@PathVariable UUID organizationId,
                                                                 @Valid @RequestBody PolicyVersionRequest request,
                                                                 Authentication authentication) {
        authorizer.requireAccess(organizationId, authentication);
        rateLimiter.requirePermit(organizationId, 120);
        var artifact = repository.registerPolicy(organizationId, UUID.randomUUID(), request.repository(),
                request.gitCommit(), request.objectKey(), request.sha256(), request.query(), clock.instant());
        URI location = URI.create("/api/v1/organizations/" + organizationId + "/policy-versions/" + artifact.versionId());
        return ResponseEntity.created(location).body(new PolicyVersionResponse(
                artifact.versionId(), artifact.objectKey(), artifact.sha256(), artifact.query()));
    }

    /**
     * Registers one benchmark dataset version.
     *
     * @param organizationId tenant path identifier
     * @param request validated dataset metadata
     * @param authentication authenticated JWT principal
     * @return HTTP 201 with registered metadata
     */
    @PostMapping("/dataset-versions")
    public ResponseEntity<DatasetVersionResponse> registerDataset(@PathVariable UUID organizationId,
                                                                   @Valid @RequestBody DatasetVersionRequest request,
                                                                   Authentication authentication) {
        authorizer.requireAccess(organizationId, authentication);
        rateLimiter.requirePermit(organizationId, 120);
        var artifact = repository.registerDataset(organizationId, UUID.randomUUID(), request.objectKey(),
                request.sha256(), request.caseCount(), request.sizeBytes(), clock.instant());
        URI location = URI.create("/api/v1/organizations/" + organizationId + "/dataset-versions/" + artifact.versionId());
        return ResponseEntity.created(location).body(new DatasetVersionResponse(
                artifact.versionId(), artifact.objectKey(), artifact.sha256(), artifact.caseCount()));
    }

    /**
     * Validated policy registration document.
     *
     * @param repository source repository identifier
     * @param gitCommit lowercase full Git commit hash
     * @param objectKey content-addressed artifact key
     * @param sha256 expected policy tree digest
     * @param query fully qualified decision query
     * @author Shelton Bumhe
     */
    public record PolicyVersionRequest(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._:/-]{1,512}") String repository,
            @NotBlank @Pattern(regexp = "[0-9a-f]{40}") String gitCommit,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}(?:/[A-Za-z0-9._-]{1,128})?") String objectKey,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String sha256,
            @NotBlank @Pattern(regexp = "data(?:\\.[A-Za-z_][A-Za-z0-9_-]*)+") String query) {}
    /**
     * Validated dataset registration document.
     *
     * @param objectKey content-addressed artifact key
     * @param sha256 expected file digest
     * @param caseCount declared benchmark case count
     * @param sizeBytes declared serialized size
     * @author Shelton Bumhe
     */
    public record DatasetVersionRequest(
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}(?:/[A-Za-z0-9._-]{1,128})?") String objectKey,
            @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String sha256,
            @Min(1) @Max(100_000L) long caseCount,
            @Min(1) @Max(67_108_864L) long sizeBytes) {}
    /**
     * Registered policy version representation.
     *
     * @param id generated version identifier
     * @param objectKey artifact key
     * @param sha256 policy digest
     * @param query decision query
     * @author Shelton Bumhe
     */
    public record PolicyVersionResponse(UUID id, String objectKey, String sha256, String query) {}

    /**
     * Registered dataset version representation.
     *
     * @param id generated version identifier
     * @param objectKey artifact key
     * @param sha256 dataset digest
     * @param caseCount benchmark case count
     * @author Shelton Bumhe
     */
    public record DatasetVersionResponse(UUID id, String objectKey, String sha256, long caseCount) {}
}
