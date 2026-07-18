package dev.opaguard.platform.messaging;

import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Configures bounded exponential retries and dead-letter recovery for Kafka consumers.
 *
 * @author Shelton Bumhe
 */
@Configuration
@ConditionalOnExpression("'${opa-guard.mode:cli}' == 'coordinator' or '${opa-guard.mode:cli}' == 'worker' or '${opa-guard.mode:cli}' == 'analyzer'")
public class KafkaConsumerConfiguration {
    @Bean
    CommonErrorHandler benchmarkErrorHandler(KafkaTemplate<Object, Object> template,
                                              org.springframework.beans.factory.ObjectProvider<BenchmarkFailureRecorder> recorderProvider) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(KafkaTopics.DEAD_LETTER_V1, record.partition()));
        var backoff = new ExponentialBackOffWithMaxRetries(7);
        backoff.setInitialInterval(1_000);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(60_000);
        return new DefaultErrorHandler((record, exception) -> {
            recoverer.accept(record, exception);
            BenchmarkFailureRecorder recorder = recorderProvider.getIfAvailable();
            if (recorder != null) recorder.record(String.valueOf(record.value()));
        }, backoff);
    }
}
