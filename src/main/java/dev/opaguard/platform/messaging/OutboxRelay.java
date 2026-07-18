package dev.opaguard.platform.messaging;

import dev.opaguard.platform.port.OutboxRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Relays claimed transactional-outbox events to Kafka with bounded waits.
 *
 * <p>Database claims use leases and skip-locked semantics, allowing many relay
 * replicas without a global lock. Publication remains at least once.</p>
 *
 * @author Shelton Bumhe
 */
@Component
@ConditionalOnProperty(name = "opa-guard.mode", havingValue = "coordinator")
public class OutboxRelay {
    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final Counter published;
    private final Counter failures;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, Clock clock, MeterRegistry registry) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.clock = clock;
        this.published = registry.counter("opa_guard_outbox_published_total");
        this.failures = registry.counter("opa_guard_outbox_publish_failures_total");
        Gauge.builder("opa_guard_outbox_backlog", outbox, OutboxRepository::unpublishedCount)
                .description("Unpublished transactional outbox events")
                .register(registry);
    }

    /**
     * Claims and publishes the next available bounded event batch.
     */
    @Scheduled(fixedDelayString = "${opa-guard.platform.outbox-delay:250ms}")
    public void publishAvailable() {
        for (var message : outbox.claimBatch(100)) {
            try {
                publish(message);
                outbox.markPublished(message.eventId(), clock.instant());
                published.increment();
            } catch (Exception exception) {
                failures.increment();
                outbox.release(message.eventId(), safeMessage(exception));
            }
        }
    }

    @CircuitBreaker(name = "kafka")
    void publish(OutboxRepository.OutboxMessage message) throws Exception {
        kafka.send(message.topic(), message.partitionKey(), message.payload())
                .get(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
    }

    private static String safeMessage(Exception exception) {
        String value = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return value.replaceAll("[\\r\\n\\t]", " ");
    }
}
