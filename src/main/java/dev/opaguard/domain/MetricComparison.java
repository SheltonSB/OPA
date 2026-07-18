package dev.opaguard.domain;

/**
 * A normalized baseline-to-candidate comparison and its configured quality gate.
 *
 * @param metric display name including units
 * @param baseline protected-branch value
 * @param candidate proposed-change value
 * @param regressionPercent signed percentage regression
 * @param thresholdPercent maximum allowed regression
 * @param thresholdExceeded whether the comparison fails its gate
 * @author Shelton Bumhe
 */
public record MetricComparison(
        String metric,
        double baseline,
        double candidate,
        double regressionPercent,
        double thresholdPercent,
        boolean thresholdExceeded) {
}
