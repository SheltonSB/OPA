package dev.opaguard.domain;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single policy evaluation and the process telemetry observed around it.
 *
 * @param wallTimeNanos end-to-end evaluation time
 * @param cpuTimeNanos CPU time consumed by the OPA process
 * @param peakMemoryBytes highest observed resident-set size
 * @param allocatedBytes bytes allocated during the observation window, when available
 * @param gcPauseNanos cumulative garbage-collection pause during the window
 * @param decision JSON decision returned by OPA
 * @author Shelton Bumhe
 */
public record Measurement(
        long wallTimeNanos,
        long cpuTimeNanos,
        long peakMemoryBytes,
        long allocatedBytes,
        long gcPauseNanos,
        JsonNode decision) {
    public Measurement(long wallTimeNanos, long cpuTimeNanos, long peakMemoryBytes, JsonNode decision) {
        this(wallTimeNanos, cpuTimeNanos, peakMemoryBytes, 0, 0, decision);
    }
}
