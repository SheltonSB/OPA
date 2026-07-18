package dev.opaguard.domain;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A named input evaluated identically against every policy version in a benchmark.
 *
 * @param id stable, human-readable case identifier
 * @param input JSON value passed to OPA as {@code input}
 * @author Shelton Bumhe
 */
public record BenchmarkCase(String id, JsonNode input) {
}
