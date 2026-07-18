package dev.opaguard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import dev.opaguard.benchmark.BenchmarkRunner;
import dev.opaguard.opa.OpaCliEvaluator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties(GuardProperties.class)
public class ApplicationConfiguration {

    @Bean
    ObjectMapper reportObjectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    BenchmarkRunner benchmarkRunner(OpaCliEvaluator evaluator) {
        return new BenchmarkRunner(evaluator);
    }
}
