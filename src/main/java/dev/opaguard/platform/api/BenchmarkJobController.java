package dev.opaguard.platform.api;

import dev.opaguard.platform.application.SubmitBenchmarkJob;
import dev.opaguard.platform.domain.BenchmarkThresholds;
import dev.opaguard.platform.port.BenchmarkJobRepository;
import dev.opaguard.platform.security.RedisRateLimiter;
import dev.opaguard.platform.security.TenantAuthorizer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Tenant-aware asynchronous command and status API for benchmark jobs.
 *
 * @author Shelton Bumhe
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/benchmark-jobs")
@ConditionalOnProperty(name = "opa-guard.mode", havingValue = "coordinator")
public class BenchmarkJobController {
    private final SubmitBenchmarkJob submitBenchmarkJob;
    private final BenchmarkJobRepository jobs;
    private final TenantAuthorizer tenantAuthorizer;
    private final RedisRateLimiter rateLimiter;

    public BenchmarkJobController(SubmitBenchmarkJob submitBenchmarkJob, BenchmarkJobRepository jobs,
                                  TenantAuthorizer tenantAuthorizer, RedisRateLimiter rateLimiter) {
        this.submitBenchmarkJob = submitBenchmarkJob;
        this.jobs = jobs;
        this.tenantAuthorizer = tenantAuthorizer;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Submits an idempotent benchmark job.
     *
     * @param organizationId tenant path identifier
     * @param idempotencyKey caller-generated retry key
     * @param request validated job definition
     * @param authentication authenticated JWT principal
     * @return HTTP 202 with the effective job and status location
     */
    @PostMapping
    public ResponseEntity<BenchmarkJobResponse> create(
            @PathVariable UUID organizationId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody CreateBenchmarkJobRequest request,
            Authentication authentication) {
        tenantAuthorizer.requireAccess(organizationId, authentication);
        rateLimiter.requirePermit(organizationId, 60);
        var value = request.thresholds();
        var thresholds = new BenchmarkThresholds(
                value.maximumAverageLatencyRegressionPercent(), value.maximumP95LatencyRegressionPercent(),
                value.maximumP99LatencyRegressionPercent(), value.maximumP999LatencyRegressionPercent(),
                value.maximumMemoryRegressionPercent(), value.maximumAllocationRateRegressionPercent(),
                value.maximumScalabilitySlopeRegressionPercent(), value.failOnDecisionChange());
        var job = submitBenchmarkJob.submit(new SubmitBenchmarkJob.Command(
                organizationId, request.baselineVersionId(), request.candidateVersionId(), request.historicalVersionId(),
                request.datasetVersionId(), idempotencyKey, thresholds,
                request.warmupIterations(), request.measuredIterations()));
        URI location = URI.create("/api/v1/organizations/" + organizationId + "/benchmark-jobs/" + job.id());
        return ResponseEntity.accepted().location(location).body(BenchmarkJobResponse.from(job));
    }

    /**
     * Returns current state for a tenant-scoped job.
     *
     * @param organizationId tenant path identifier
     * @param jobId benchmark job identifier
     * @param authentication authenticated JWT principal
     * @return current job representation
     */
    @GetMapping("/{jobId}")
    public BenchmarkJobResponse get(@PathVariable UUID organizationId, @PathVariable UUID jobId,
                                    Authentication authentication) {
        tenantAuthorizer.requireAccess(organizationId, authentication);
        return jobs.findById(organizationId, jobId).map(BenchmarkJobResponse::from)
                .orElseThrow(() -> new JobNotFoundException(jobId));
    }

    static final class JobNotFoundException extends RuntimeException {
        JobNotFoundException(UUID id) { super("Benchmark job not found: " + id); }
    }
}
