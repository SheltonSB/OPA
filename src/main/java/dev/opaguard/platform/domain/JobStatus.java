package dev.opaguard.platform.domain;

/**
 * Lifecycle states of a distributed benchmark job.
 *
 * @author Shelton Bumhe
 */
public enum JobStatus {
    QUEUED,
    RUNNING,
    ANALYZING,
    PASSED,
    FAILED,
    ERROR,
    CANCELLED;

    public boolean terminal() {
        return this == PASSED || this == FAILED || this == ERROR || this == CANCELLED;
    }
}
