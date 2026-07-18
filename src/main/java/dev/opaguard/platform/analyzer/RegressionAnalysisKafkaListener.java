package dev.opaguard.platform.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.opaguard.platform.domain.BenchmarkExecutionCompleted;
import dev.opaguard.platform.messaging.KafkaTopics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Kafka ingress adapter for completed distributed benchmark executions.
 *
 * <p>Offsets are acknowledged only after analysis completes, preserving
 * at-least-once delivery. The downstream service is idempotent for terminal jobs.</p>
 *
 * @author Shelton Bumhe
 */
@Component
@ConditionalOnProperty(name = "opa-guard.mode", havingValue = "analyzer")
public class RegressionAnalysisKafkaListener {
    private final ObjectMapper objectMapper;
    private final RegressionAnalysisService service;

    public RegressionAnalysisKafkaListener(ObjectMapper objectMapper, RegressionAnalysisService service) {
        this.objectMapper = objectMapper; this.service = service;
    }

    /**
     * Validates and processes a versioned execution event.
     *
     * @param payload serialized event JSON
     * @param acknowledgment manual Kafka acknowledgment
     * @throws Exception when deserialization or processing fails so Kafka can retry
     */
    @KafkaListener(topics = KafkaTopics.SHARD_COMPLETED_V1, groupId = "opa-guard-analyzers-v1")
    public void receive(String payload, Acknowledgment acknowledgment) throws Exception {
        BenchmarkExecutionCompleted event = objectMapper.readValue(payload, BenchmarkExecutionCompleted.class);
        if (event.schemaVersion() != 1) throw new IllegalArgumentException("Unsupported execution event schema version");
        service.analyze(event);
        acknowledgment.acknowledge();
    }
}
