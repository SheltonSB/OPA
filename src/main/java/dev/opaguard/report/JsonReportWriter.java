package dev.opaguard.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opaguard.domain.GuardReport;
import dev.opaguard.exception.GuardException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class JsonReportWriter implements ReportWriter {
    private final ObjectMapper objectMapper;

    public JsonReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
