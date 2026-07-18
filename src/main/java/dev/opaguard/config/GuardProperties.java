package dev.opaguard.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * Validated configuration for local CI benchmark execution.
 *
 * @param opaExecutable trusted OPA executable name or absolute path
 * @param query fully qualified decision query
 * @param baselinePolicy baseline policy path
 * @param candidatePolicy candidate policy path
 * @param benchmarkDataset benchmark input dataset
 * @param maximumLatencyRegressionPercent allowed latency increase
 * @param maximumMemoryRegressionPercent allowed memory increase
 * @param minimumIterations measured iterations per dataset
 * @param warmupIterations unmeasured warmup iterations
 * @param markdownOutput Markdown report destination
 * @param jsonOutput JSON report destination
 * @param processTimeoutSeconds per-evaluation timeout
 * @param failOnDecisionChange whether correctness differences fail the run
 * @author Shelton Bumhe
 */
@Validated
@ConfigurationProperties(prefix = "opa-guard")
public record GuardProperties(
        @NotBlank String opaExecutable,
        @NotBlank String query,
        Path baselinePolicy,
        Path candidatePolicy,
        Path benchmarkDataset,
        @PositiveOrZero double maximumLatencyRegressionPercent,
        @PositiveOrZero double maximumMemoryRegressionPercent,
        @Min(1) int minimumIterations,
        @PositiveOrZero int warmupIterations,
        Path markdownOutput,
        Path jsonOutput,
        @Min(1) long processTimeoutSeconds,
        boolean failOnDecisionChange) {
}
