package dev.opaguard.platform.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opaguard.platform.domain.JobStatus;
import dev.opaguard.platform.port.BenchmarkJobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * Marks identifiable jobs as errored after Kafka retry exhaustion.
 *
 * <p>Malformed dead-letter payloads are retained for operator repair and never
 * used to mutate a job because they lack a trustworthy tenant identity.</p>
 *
 * @author Shelton Bumhe
 */
@Component
@ConditionalOnExpression("'${opa-guard.mode:cli}' == 'worker' or '${opa-guard.mode:cli}' == 'analyzer'")
public class BenchmarkFailureRecorder {
    private final ObjectMapper objectMapper;
    private final BenchmarkJobRepository jobs;
    private final Clock clock;

    public BenchmarkFailureRecorder(ObjectMapper objectMapper, BenchmarkJobRepository jobs, Clock clock) {
        this.objectMapper = objectMapper; this.jobs = jobs; this.clock = clock;
    }

    /**
     * Records terminal failure for the job identified by an event payload.
     *
     * @param payload original dead-lettered event JSON
     */
    public void record(String payload) {
        EventIdentity identity;
        try {
            JsonNode root = objectMapper.readTree(payload);
            identity = new EventIdentity(UUID.fromString(text(root, "organization_id", "organizationId")),
                    UUID.fromString(text(root, "job_id", "jobId")));
        } catch (Exception malformed) {
            // Malformed events remain in the DLT for operator repair; no trustworthy job identity exists.
            return;
        }
        jobs.findById(identity.organizationId(), identity.jobId()).ifPresent(job -> {
            if (!job.status().terminal()) {
                job.transitionTo(JobStatus.ERROR, clock.instant());
                jobs.update(job);
            }
        });
    }

    private static String text(JsonNode root, String primary, String fallback) {
        JsonNode value = root.has(primary) ? root.get(primary) : root.get(fallback);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException("Missing event identity");
        return value.asText();
    }

    private record EventIdentity(UUID organizationId, UUID jobId) {}
}
