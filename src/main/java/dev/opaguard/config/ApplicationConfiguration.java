package dev.opaguard.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import dev.opaguard.benchmark.BenchmarkRunner;
import dev.opaguard.benchmark.ScalabilityBenchmarkRunner;
import dev.opaguard.opa.OpaCliEvaluator;
import dev.opaguard.opa.PolicyEvaluator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Declares shared infrastructure-free application services and constrained JSON handling.
 *
 * @author Shelton Bumhe
 */
@Configuration
@EnableConfigurationProperties(GuardProperties.class)
public class ApplicationConfiguration {

    @Bean
    ObjectMapper reportObjectMapper() {
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(100)
                        .maxStringLength(1_048_576)
                        .maxNumberLength(1000)
                        .build())
                .build();
        return new ObjectMapper(factory).findAndRegisterModules();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    BenchmarkRunner benchmarkRunner(PolicyEvaluator evaluator) {
        return new BenchmarkRunner(evaluator);
    }

    @Bean
    ScalabilityBenchmarkRunner scalabilityBenchmarkRunner(PolicyEvaluator evaluator) {
        return new ScalabilityBenchmarkRunner(evaluator);
    }
}
