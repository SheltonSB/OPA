package dev.opaguard.opa;

import java.util.HashMap;
import java.util.Map;

/**
 * Parses the bounded, label-free OPA Prometheus counters required by the benchmark harness.
 *
 * <p>Unknown metrics and malformed numeric values are ignored so telemetry
 * evolution cannot invalidate an otherwise correct authorization benchmark.</p>
 *
 * @author Shelton Bumhe
 */
final class OpaPrometheusMetrics {
    private OpaPrometheusMetrics() {}

    static Snapshot parse(String prometheus) {
        Map<String, Double> values = new HashMap<>();
        for (String line : prometheus.lines().toList()) {
            if (line.isBlank() || line.startsWith("#") || line.indexOf('{') >= 0) continue;
            String[] parts = line.trim().split("\\s+", 2);
            if (parts.length != 2 || !parts[0].matches("[a-zA-Z_:][a-zA-Z0-9_:]*")) continue;
            try { values.put(parts[0], Double.parseDouble(parts[1])); }
            catch (NumberFormatException ignored) { }
        }
        return new Snapshot(
                toLong(values.getOrDefault("go_memstats_alloc_bytes_total", 0d)),
                toLong(values.getOrDefault("go_gc_duration_seconds_sum", 0d) * 1_000_000_000d),
                toLong(values.getOrDefault("process_resident_memory_bytes", 0d)));
    }

    private static long toLong(double value) {
        if (!Double.isFinite(value) || value <= 0) return 0;
        return value >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) value;
    }

    /**
     * OPA process counters extracted from one metrics response.
     *
     * @param allocatedBytes cumulative Go allocation bytes
     * @param gcPauseNanos cumulative Go garbage-collection pause
     * @param residentMemoryBytes current process resident-set size
     * @author Shelton Bumhe
     */
    record Snapshot(long allocatedBytes, long gcPauseNanos, long residentMemoryBytes) {}
}
