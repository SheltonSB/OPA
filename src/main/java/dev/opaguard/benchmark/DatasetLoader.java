package dev.opaguard.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opaguard.domain.BenchmarkCase;
import dev.opaguard.exception.GuardException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class DatasetLoader {
    private final ObjectMapper objectMapper;

    public DatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<BenchmarkCase> load(Path datasetPath) {
        if (datasetPath == null || !Files.isRegularFile(datasetPath)) {
            throw new GuardException("Benchmark dataset does not exist: " + datasetPath);
        }
        try {
            JsonNode root = objectMapper.readTree(datasetPath.toFile());
            JsonNode cases = root.isArray() ? root : root.path("cases");
            if (!cases.isArray() || cases.isEmpty()) {
                throw new GuardException("Benchmark dataset must be a non-empty JSON array or contain a non-empty 'cases' array");
            }
            List<BenchmarkCase> result = new ArrayList<>();
            for (int index = 0; index < cases.size(); index++) {
                JsonNode item = cases.get(index);
                boolean wrapped = item.isObject() && item.has("input");
                String id = wrapped && item.hasNonNull("id") ? item.get("id").asText() : "case-" + (index + 1);
                JsonNode input = wrapped ? item.get("input") : item;
                result.add(new BenchmarkCase(id, input));
            }
            long uniqueIds = result.stream().map(BenchmarkCase::id).distinct().count();
            if (uniqueIds != result.size()) {
                throw new GuardException("Benchmark case ids must be unique");
            }
            return List.copyOf(result);
        } catch (IOException exception) {
            throw new GuardException("Unable to read benchmark dataset " + datasetPath + ": " + exception.getMessage(), exception);
        }
    }
}
