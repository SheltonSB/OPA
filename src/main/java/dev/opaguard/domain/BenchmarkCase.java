package dev.opaguard.domain;

import com.fasterxml.jackson.databind.JsonNode;

public record BenchmarkCase(String id, JsonNode input) {
}
