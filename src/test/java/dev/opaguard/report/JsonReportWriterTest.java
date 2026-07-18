package dev.opaguard.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import dev.opaguard.domain.BenchmarkMetrics;
import dev.opaguard.domain.GuardReport;
import dev.opaguard.domain.PolicyBenchmark;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonReportWriterTest {
    @Test
    void serializesRelativePolicyPathsWithoutExpandingThemToWorkstationUris(@TempDir Path temporaryDirectory)
            throws Exception {
        var metrics = new BenchmarkMetrics(1, 1, 1, 1_000, 1, 100, 1);
        var benchmark = new PolicyBenchmark("main", Path.of("policy"), metrics,
                Map.of("case", BooleanNode.TRUE));
        var report = new GuardReport("PASS", Instant.EPOCH, benchmark, benchmark,
                List.of(), List.of(), "None", "None");
        Path output = temporaryDirectory.resolve("report.json");

        new JsonReportWriter(new ObjectMapper().findAndRegisterModules()).write(report, output);

        String json = java.nio.file.Files.readString(output);
        assertThat(json).contains("\"policy_path\" : \"policy\"");
        assertThat(json).doesNotContain("file://").doesNotContain(System.getProperty("user.home"));
    }
}
