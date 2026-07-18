package dev.opaguard.domain;

import com.fasterxml.jackson.databind.JsonNode;

public record DecisionMismatch(String caseId, JsonNode baseline, JsonNode candidate) {
}
