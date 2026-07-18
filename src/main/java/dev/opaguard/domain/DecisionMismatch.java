package dev.opaguard.domain;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Records a benchmark case whose baseline and candidate decisions differ.
 *
 * @param caseId benchmark dataset identifier
 * @param baseline decision produced by the baseline policy
 * @param candidate decision produced by the candidate policy
 * @author Shelton Bumhe
 */
public record DecisionMismatch(String caseId, JsonNode baseline, JsonNode candidate) {
}
