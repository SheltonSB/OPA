package dev.opaguard.platform.port;

import dev.opaguard.platform.domain.BenchmarkJob;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the benchmark job aggregate.
 *
 * @author Shelton Bumhe
 */
public interface BenchmarkJobRepository {
    /**
     * Atomically creates a job unless its tenant idempotency key already exists.
     *
     * @param job proposed aggregate
     * @return existing or newly created aggregate and creation indicator
     */
    CreationResult createIfAbsent(BenchmarkJob job);

    /**
     * Looks up one job within a tenant boundary.
     *
     * @param organizationId owning tenant
     * @param jobId job identifier
     * @return matching job, if present
     */
    Optional<BenchmarkJob> findById(UUID organizationId, UUID jobId);

    /**
     * Persists an aggregate using optimistic concurrency control.
     *
     * @param job updated aggregate
     */
    void update(BenchmarkJob job);

    /**
     * Result of an idempotent creation attempt.
     *
     * @param job effective aggregate
     * @param created whether this call inserted it
     * @author Shelton Bumhe
     */
    record CreationResult(BenchmarkJob job, boolean created) {}
}
