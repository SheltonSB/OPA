package dev.opaguard.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opaguard.exception.GuardException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasetLoaderTest {
    @TempDir
    Path tempDir;

    private final DatasetLoader loader = new DatasetLoader(new ObjectMapper());

    @Test
    void supportsWrappedAndRawInputs() throws Exception {
        Path dataset = tempDir.resolve("cases.json");
        Files.writeString(dataset, "[{\"id\":\"named\",\"input\":{\"x\":1}},{\"x\":2}]");

        var cases = loader.load(dataset);

        assertThat(cases).hasSize(2);
        assertThat(cases.get(0).id()).isEqualTo("named");
        assertThat(cases.get(0).input().path("x").asInt()).isEqualTo(1);
        assertThat(cases.get(1).id()).isEqualTo("case-2");
    }

    @Test
    void rejectsDuplicateCaseIds() throws Exception {
        Path dataset = tempDir.resolve("duplicates.json");
        Files.writeString(dataset, "[{\"id\":\"x\",\"input\":{}},{\"id\":\"x\",\"input\":{}}]");

        assertThatThrownBy(() -> loader.load(dataset))
                .isInstanceOf(GuardException.class)
                .hasMessageContaining("unique");
    }
}
