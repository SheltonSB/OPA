package dev.opaguard.opa;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpaPrometheusMetricsTest {
    @Test
    void extractsAllocationGcAndResidentMemory() {
        String metrics = """
                # HELP go_memstats_alloc_bytes_total Total bytes allocated
                go_memstats_alloc_bytes_total 1.25e+06
                go_gc_duration_seconds_sum 0.125
                process_resident_memory_bytes 6.7108864e+07
                metric_with_labels{label="ignored"} 99
                """;

        var result = OpaPrometheusMetrics.parse(metrics);

        assertThat(result.allocatedBytes()).isEqualTo(1_250_000);
        assertThat(result.gcPauseNanos()).isEqualTo(125_000_000);
        assertThat(result.residentMemoryBytes()).isEqualTo(67_108_864);
    }
}
