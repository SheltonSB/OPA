package dev.opaguard.domain;

import com.fasterxml.jackson.databind.JsonNode;

public record Measurement(
        long wallTimeNanos,
        long cpuTimeNanos,
        long peakMemoryBytes,
        JsonNode decision) {
}
