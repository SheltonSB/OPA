package dev.opaguard.platform.analysis;

import dev.opaguard.platform.domain.BenchmarkJobRequested;
import dev.opaguard.platform.domain.DatasetArtifact;
import dev.opaguard.platform.domain.PolicyArtifact;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Produces a stable content fingerprint for safe incremental benchmark reuse.
 *
 * <p>The fingerprint includes tenant, artifact digests, query, harness version,
 * iteration counts, and thresholds. A change to any execution input invalidates
 * the cached result.</p>
 *
 * @author Shelton Bumhe
 */
public final class BenchmarkFingerprint {
    private BenchmarkFingerprint() {}

    /**
     * Calculates the canonical SHA-256 benchmark fingerprint.
     *
     * @param event requested execution settings
     * @param baseline baseline policy metadata
     * @param candidate candidate policy metadata
     * @param dataset dataset metadata
     * @param harnessVersion benchmark harness semantic version
     * @return lowercase hexadecimal SHA-256 digest
     */
    public static String calculate(BenchmarkJobRequested event, PolicyArtifact baseline,
                                   PolicyArtifact candidate, DatasetArtifact dataset, String harnessVersion) {
        String canonical = String.join("\n",
                "v1", event.organizationId().toString(), baseline.sha256(), candidate.sha256(), dataset.sha256(),
                String.valueOf(event.historicalVersionId()), baseline.query(),
                Integer.toString(event.warmupIterations()), Integer.toString(event.measuredIterations()),
                event.thresholds().toString(), harnessVersion);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
