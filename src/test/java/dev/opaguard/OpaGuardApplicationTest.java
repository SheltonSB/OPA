package dev.opaguard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies launcher argument normalization without starting a Spring context.
 *
 * @author Shelton Bumhe
 */
class OpaGuardApplicationTest {

    @TempDir
    Path tempDir;

    @Test
    void translatesSeparateConfigArgumentIntoSpringLocation() throws Exception {
        Path config = Files.createFile(tempDir.resolve("opa-guard.yml"));

        String[] normalized = OpaGuardApplication.normalizeArguments(
                new String[]{"--config", config.toString(), "--opa-guard.mode=cli"});

        assertThat(normalized)
                .containsExactly("--opa-guard.mode=cli",
                        "--spring.config.additional-location=file:" + config.toAbsolutePath().normalize());
    }

    @Test
    void translatesEqualsConfigArgumentAndPreservesExistingLocations() {
        String[] normalized = OpaGuardApplication.normalizeArguments(new String[]{
                "--config=classpath:/ci.yml",
                "--spring.config.additional-location=file:./defaults.yml",
                "--opa-guard.minimum-iterations=1"});

        assertThat(normalized)
                .containsExactly("--opa-guard.minimum-iterations=1",
                        "--spring.config.additional-location=file:./defaults.yml,classpath:/ci.yml");
    }

    @Test
    void leavesArgumentsUntouchedWhenConfigOptionIsAbsent() {
        String[] args = {"--opa-guard.mode=cli", "--debug"};

        assertThat(OpaGuardApplication.normalizeArguments(args)).containsExactly(args);
    }

    @Test
    void rejectsConfigWithoutAPath() {
        assertThatThrownBy(() -> OpaGuardApplication.normalizeArguments(new String[]{"--config"}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing value for --config");
    }
}
