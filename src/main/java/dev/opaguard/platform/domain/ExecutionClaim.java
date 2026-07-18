package dev.opaguard.platform.domain;

/**
 * Result of attempting to acquire the execution lease for a benchmark job.
 *
 * @author Shelton Bumhe
 */
public enum ExecutionClaim {
    /** This worker owns the lease and may execute the job. */
    CLAIMED,
    /** Another worker owns a live lease; Kafka should retry this delivery. */
    LEASED,
    /** The job already moved beyond execution and this delivery is a duplicate. */
    COMPLETE
}
