package dev.opaguard.integration;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class KafkaRedeliveryIntegrationTest {
    private static final String TOPIC = "opa.guard.redelivery-test.v1";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    @Test
    void uncommittedRecordIsRedeliveredWithTheSameEventIdentity() throws Exception {
        String eventId = UUID.randomUUID().toString();
        try (var producer = new KafkaProducer<String, String>(producerProperties())) {
            producer.send(new ProducerRecord<>(TOPIC, eventId, "{\"eventId\":\"" + eventId + "\"}"))
                    .get();
        }

        String group = "redelivery-" + UUID.randomUUID();
        String firstDelivery;
        try (var consumer = consumer(group)) {
            firstDelivery = pollValue(consumer);
            // Deliberately close without committing to model a worker process crash.
        }
        String secondDelivery;
        try (var consumer = consumer(group)) {
            secondDelivery = pollValue(consumer);
            consumer.commitSync();
        }

        assertThat(firstDelivery).isEqualTo(secondDelivery).contains(eventId);
    }

    private KafkaConsumer<String, String> consumer(String group) {
        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(TOPIC));
        return consumer;
    }

    private String pollValue(KafkaConsumer<String, String> consumer) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            var records = consumer.poll(Duration.ofMillis(250));
            if (!records.isEmpty()) return records.iterator().next().value();
        }
        throw new AssertionError("Kafka record was not delivered before the deadline");
    }

    private Map<String, Object> producerProperties() {
        return Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    }
}
