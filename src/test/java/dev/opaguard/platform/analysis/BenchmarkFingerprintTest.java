package dev.opaguard.platform.analysis;

import dev.opaguard.platform.domain.BenchmarkJobRequested;
import dev.opaguard.platform.domain.BenchmarkThresholds;
import dev.opaguard.platform.domain.DatasetArtifact;
import dev.opaguard.platform.domain.PolicyArtifact;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkFingerprintTest {
    @Test
    void isStableButTenantIsolated() {
        UUID org = UUID.randomUUID();
        var baseline = new PolicyArtifact(UUID.randomUUID(), org, "a", "a".repeat(64), "data.authz.allow");
        var candidate = new PolicyArtifact(UUID.randomUUID(), org, "b", "b".repeat(64), "data.authz.allow");
        var dataset = new DatasetArtifact(UUID.randomUUID(), org, "d", "d".repeat(64), 10);
        var first = event(org);

        String fingerprint = BenchmarkFingerprint.calculate(first, baseline, candidate, dataset, "1");
        String repeated = BenchmarkFingerprint.calculate(first, baseline, candidate, dataset, "1");
        String otherTenant = BenchmarkFingerprint.calculate(event(UUID.randomUUID()), baseline, candidate, dataset, "1");

        assertThat(fingerprint).hasSize(64).isEqualTo(repeated).isNotEqualTo(otherTenant);
    }

    private BenchmarkJobRequested event(UUID organizationId) {
        BigDecimal ten = BigDecimal.TEN;
        return new BenchmarkJobRequested(UUID.randomUUID(), UUID.randomUUID(), organizationId,
                UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(),
                new BenchmarkThresholds(ten, ten, ten, ten, ten, ten, ten, true),
                5, 30, Instant.EPOCH, 1);
    }
}
