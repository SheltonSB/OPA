package dev.opaguard.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.opaguard.domain.GuardReport;
import dev.opaguard.exception.GuardException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serializes guard reports as stable, pretty-printed snake-case JSON.
 *
 * @author Shelton Bumhe
 */
@Component
public class JsonReportWriter implements ReportWriter {
    private final ObjectMapper objectMapper;

    /**
     * Creates a writer with an isolated mapper configuration.
     *
     * @param objectMapper application JSON mapper to copy
     */
    public JsonReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Override
    public void write(GuardReport report, Path output) {
        try {
            createParent(output);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        } catch (IOException exception) {
            throw new GuardException("Unable to write JSON report to " + output, exception);
        }
    }

    static void createParent(Path output) throws IOException {
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
