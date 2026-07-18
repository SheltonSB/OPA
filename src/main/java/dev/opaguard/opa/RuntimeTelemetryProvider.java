package dev.opaguard.opa;

import java.nio.file.Path;

/**
 * Optional capability for evaluators that expose cumulative OPA runtime counters.
 *
 * @author Shelton Bumhe
 */
public interface RuntimeTelemetryProvider {
    /**
     * Captures cumulative allocation and garbage-collection counters.
     *
     * @param policyPath policy runtime whose counters are requested
     * @return current counter values, or zero-valued telemetry when unavailable
     */
    RuntimeTelemetry snapshot(Path policyPath);

    /**
     * Cumulative runtime counters used to calculate benchmark deltas.
     *
     * @param allocatedBytes cumulative allocated bytes
     * @param gcPauseNanos cumulative garbage-collection pause time
     * @author Shelton Bumhe
     */
    record RuntimeTelemetry(long allocatedBytes, long gcPauseNanos) {
        /**
         * Returns a safe zero-valued snapshot for evaluators without runtime telemetry.
         *
         * @return unavailable telemetry sentinel
         */
        public static RuntimeTelemetry unavailable() { return new RuntimeTelemetry(0, 0); }
    }
}
