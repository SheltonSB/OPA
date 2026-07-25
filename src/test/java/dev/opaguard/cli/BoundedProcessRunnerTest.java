package dev.opaguard.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BoundedProcessRunnerTest {
    @Test
    void drainsBothOutputStreamsWithoutDeadlocking() throws Exception {
        Path shell = Path.of("/bin/sh");
        assumeTrue(Files.isExecutable(shell));
        String script = "i=0; while [ $i -lt 100000 ]; do printf x; printf y >&2; i=$((i+1)); done";

        BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                List.of(shell.toString(), "-c", script), Path.of("."), Duration.ofSeconds(5), 200_000);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).hasSize(100_000);
        assertThat(result.stderr()).hasSize(100_000);
    }
}
