package dev.opaguard.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

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
