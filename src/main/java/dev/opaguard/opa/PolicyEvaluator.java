package dev.opaguard.opa;

import com.fasterxml.jackson.databind.JsonNode;
import dev.opaguard.domain.Measurement;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Port used by benchmark services to evaluate a policy without depending on an OPA transport.
 *
 * <p>Implementations must enforce the supplied deadline and return both the
 * authorization decision and measurements for that single evaluation.</p>
 *
 * @author Shelton Bumhe
 */
public interface PolicyEvaluator {
    /**
     * Evaluates one input against a policy decision.
     *
     * @param policyPath policy bundle file or directory
     * @param query fully qualified OPA data query
     * @param input JSON value made available to Rego as {@code input}
     * @param timeout maximum evaluation duration
     * @return decision and resource measurements
     */
    Measurement evaluate(Path policyPath, String query, JsonNode input, Duration timeout);
}
