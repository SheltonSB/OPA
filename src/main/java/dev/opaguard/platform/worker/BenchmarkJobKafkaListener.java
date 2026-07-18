package dev.opaguard.platform.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opaguard.platform.domain.BenchmarkJobRequested;
import dev.opaguard.platform.messaging.KafkaTopics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka ingress adapter for benchmark job requests.
 *
 * <p>Manual acknowledgment occurs only after the worker has durably appended
 * its result event or reused a valid incremental result.</p>
 *
 * @author Shelton Bumhe
 */
@Component
@ConditionalOnProperty(name = "opa-guard.mode", havingValue = "worker")
public class BenchmarkJobKafkaListener {
    private final ObjectMapper objectMapper;
    private final DistributedBenchmarkWorker worker;

    public BenchmarkJobKafkaListener(ObjectMapper objectMapper, DistributedBenchmarkWorker worker) {
        this.objectMapper = objectMapper; this.worker = worker;
    }

    /**
     * Validates and executes one versioned job event.
     *
     * @param payload serialized request event
     * @param acknowledgment manual Kafka acknowledgment
     * @throws Exception when processing fails so the configured retry policy applies
     */
    @KafkaListener(topics = KafkaTopics.JOB_REQUESTED_V1, groupId = "opa-guard-workers-v1")
    public void receive(String payload, Acknowledgment acknowledgment) throws Exception {
        BenchmarkJobRequested event = objectMapper.readValue(payload, BenchmarkJobRequested.class);
        if (event.schemaVersion() != 1) throw new IllegalArgumentException("Unsupported benchmark event schema version");
        worker.execute(event);
        acknowledgment.acknowledge();
    }
}
