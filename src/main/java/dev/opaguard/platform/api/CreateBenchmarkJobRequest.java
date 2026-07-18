package dev.opaguard.platform.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Validated request for an asynchronous benchmark comparison.
 *
 * @param baselineVersionId protected-branch policy version
 * @param candidateVersionId proposed policy version
 * @param historicalVersionId optional historical policy version
 * @param datasetVersionId immutable dataset version
 * @param warmupIterations unmeasured warmup iterations
 * @param measuredIterations measured iterations
 * @param thresholds performance and correctness gates
 * @author Shelton Bumhe
 */
public record CreateBenchmarkJobRequest(
        @NotNull UUID baselineVersionId,
        @NotNull UUID candidateVersionId,
        UUID historicalVersionId,
        @NotNull UUID datasetVersionId,
        @Min(0) @Max(10_000) int warmupIterations,
        @Min(1) @Max(1_000_000) int measuredIterations,
        @NotNull @Valid Thresholds thresholds) {

    /**
     * Percentage-based regression gates supplied by API clients.
     *
     * @param maximumAverageLatencyRegressionPercent average latency gate
     * @param maximumP95LatencyRegressionPercent p95 latency gate
     * @param maximumP99LatencyRegressionPercent p99 latency gate
     * @param maximumP999LatencyRegressionPercent p999 latency gate
     * @param maximumMemoryRegressionPercent peak memory gate
     * @param maximumAllocationRateRegressionPercent allocation-rate gate
     * @param maximumScalabilitySlopeRegressionPercent scaling-efficiency gate
     * @param failOnDecisionChange whether correctness differences fail the job
     * @author Shelton Bumhe
     */
    public record Thresholds(
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal maximumAverageLatencyRegressionPercent,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal maximumP95LatencyRegressionPercent,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal maximumP99LatencyRegressionPercent,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal maximumP999LatencyRegressionPercent,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal maximumMemoryRegressionPercent,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal maximumAllocationRateRegressionPercent,
            @NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal maximumScalabilitySlopeRegressionPercent,
            boolean failOnDecisionChange) {}
}
