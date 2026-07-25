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

/**
 * Loads and validates bounded JSON benchmark datasets.
 *
 * <p>Both a top-level array and an object containing a {@code cases} array are
 * accepted. Symlinks, duplicate case identifiers, empty inputs, and oversized
 * datasets are rejected before a benchmark starts.</p>
 *
 * @author Shelton Bumhe
 */
@Component
public class DatasetLoader {
    static final long MAX_DATASET_BYTES = 64L * 1024 * 1024;
    static final int MAX_CASES = 100_000;
    private final ObjectMapper objectMapper;

    /**
     * Creates a loader using the application's constrained JSON mapper.
     *
     * @param objectMapper mapper used to parse the dataset
     */
    public DatasetLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Loads a benchmark dataset from a regular local file.
     *
     * @param datasetPath dataset JSON path
     * @return immutable ordered benchmark cases
     * @throws GuardException when the path or document violates safety constraints
     */
    public List<BenchmarkCase> load(Path datasetPath) {
        if (datasetPath == null || !Files.isRegularFile(datasetPath)) {
            throw new GuardException("Benchmark dataset does not exist: " + datasetPath);
        }
        try {
            if (Files.isSymbolicLink(datasetPath)) {
                throw new GuardException("Benchmark dataset must not be a symbolic link");
            }
            long datasetBytes = Files.size(datasetPath);
            if (datasetBytes > MAX_DATASET_BYTES) {
                throw new GuardException("Benchmark dataset exceeds the 64 MiB safety limit");
            }
            JsonNode root = objectMapper.readTree(datasetPath.toFile());
            JsonNode cases = root != null && root.isArray() ? root
                    : root == null ? null : root.path("cases");
            if (cases == null || !cases.isArray() || cases.isEmpty()) {
                throw new GuardException("Benchmark dataset must be a non-empty JSON array or contain a non-empty 'cases' array");
            }
            if (cases.size() > MAX_CASES) {
                throw new GuardException("Benchmark dataset exceeds the 100,000 case safety limit");
            }
            List<BenchmarkCase> result = new ArrayList<>();
            for (int index = 0; index < cases.size(); index++) {
                JsonNode item = cases.get(index);
                if (item == null || item.isMissingNode()) {
                    throw new GuardException("Benchmark case " + (index + 1) + " is missing");
                }
                boolean wrapped = item.isObject() && item.has("input");
                String id = wrapped && item.hasNonNull("id") ? item.get("id").asText() : "case-" + (index + 1);
                if (id.isBlank()) {
                    throw new GuardException("Benchmark case ids must not be blank");
                }
                JsonNode input = wrapped ? item.get("input") : item;
                if (input == null || input.isMissingNode()) {
                    throw new GuardException("Benchmark case " + id + " must contain input");
                }
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
