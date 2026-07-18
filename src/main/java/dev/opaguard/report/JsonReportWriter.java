package dev.opaguard.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.module.SimpleModule;
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
        SimpleModule portablePaths = new SimpleModule()
                .addSerializer(Path.class, new com.fasterxml.jackson.databind.JsonSerializer<>() {
                    @Override
                    public void serialize(Path value, com.fasterxml.jackson.core.JsonGenerator generator,
                                          com.fasterxml.jackson.databind.SerializerProvider serializers)
                            throws IOException {
                        generator.writeString(value.normalize().toString().replace('\\', '/'));
                    }
                });
        this.objectMapper = objectMapper.copy()
                .registerModule(portablePaths)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
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
