package dev.opaguard.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
 * @param policyPath repository-relative policy path used by the developer compare command
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
        boolean failOnDecisionChange,
        Path policyPath) {

    /**
     * Canonical constructor used by Spring Boot configuration-properties binding.
     */
    @ConstructorBinding
    public GuardProperties {
    }

    /**
     * Backward-compatible constructor for callers that predate the developer
     * command's explicit policy-path setting.
     *
     * @param opaExecutable trusted OPA executable
     * @param query fully qualified decision query
     * @param baselinePolicy baseline policy path
     * @param candidatePolicy candidate policy path
     * @param benchmarkDataset benchmark dataset path
     * @param maximumLatencyRegressionPercent latency threshold
     * @param maximumMemoryRegressionPercent memory threshold
     * @param minimumIterations measured iterations
     * @param warmupIterations warmup iterations
     * @param markdownOutput Markdown report path
     * @param jsonOutput JSON report path
     * @param processTimeoutSeconds evaluation timeout
     * @param failOnDecisionChange correctness gate
     */
    public GuardProperties(String opaExecutable, String query, Path baselinePolicy, Path candidatePolicy,
                           Path benchmarkDataset, double maximumLatencyRegressionPercent,
                           double maximumMemoryRegressionPercent, int minimumIterations, int warmupIterations,
                           Path markdownOutput, Path jsonOutput, long processTimeoutSeconds,
                           boolean failOnDecisionChange) {
        this(opaExecutable, query, baselinePolicy, candidatePolicy, benchmarkDataset,
                maximumLatencyRegressionPercent, maximumMemoryRegressionPercent, minimumIterations,
                warmupIterations, markdownOutput, jsonOutput, processTimeoutSeconds,
                failOnDecisionChange, Path.of("policy"));
    }
}
