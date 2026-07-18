package dev.opaguard.opa;

import com.fasterxml.jackson.databind.JsonNode;
import dev.opaguard.domain.Measurement;

import java.nio.file.Path;
import java.time.Duration;

public interface PolicyEvaluator {
    Measurement evaluate(Path policyPath, String query, JsonNode input, Duration timeout);
}
