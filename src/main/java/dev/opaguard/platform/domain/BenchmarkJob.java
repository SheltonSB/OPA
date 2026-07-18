package dev.opaguard.platform.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root that owns benchmark identity, immutable inputs, and valid state transitions.
 *
 * <p>Instances are mutated only through {@link #transitionTo(JobStatus, Instant)}. The version
 * is advanced with each transition and is used for optimistic persistence.</p>
 *
 * @author Shelton Bumhe
 */
public final class BenchmarkJob {
    private final UUID id;
    private final UUID organizationId;
    private final UUID baselineVersionId;
    private final UUID candidateVersionId;
    private final UUID historicalVersionId;
    private final UUID datasetVersionId;
    private final String idempotencyKey;
    private final BenchmarkThresholds thresholds;
    private final int warmupIterations;
    private final int measuredIterations;
    private final Instant createdAt;
    private JobStatus status;
    private Instant updatedAt;
    private long version;

    private BenchmarkJob(Builder builder) {
        this.id = Objects.requireNonNull(builder.id);
        this.organizationId = Objects.requireNonNull(builder.organizationId);
        this.baselineVersionId = Objects.requireNonNull(builder.baselineVersionId);
        this.candidateVersionId = Objects.requireNonNull(builder.candidateVersionId);
        this.historicalVersionId = builder.historicalVersionId;
        this.datasetVersionId = Objects.requireNonNull(builder.datasetVersionId);
        this.idempotencyKey = requireText(builder.idempotencyKey, "idempotencyKey", 128);
        this.thresholds = Objects.requireNonNull(builder.thresholds);
        if (builder.warmupIterations < 0 || builder.warmupIterations > 10_000) {
            throw new IllegalArgumentException("warmupIterations must be between 0 and 10000");
        }
        if (builder.measuredIterations < 1 || builder.measuredIterations > 1_000_000) {
            throw new IllegalArgumentException("measuredIterations must be between 1 and 1000000");
        }
        this.warmupIterations = builder.warmupIterations;
        this.measuredIterations = builder.measuredIterations;
        this.status = builder.status == null ? JobStatus.QUEUED : builder.status;
        this.createdAt = Objects.requireNonNull(builder.createdAt);
        this.updatedAt = builder.updatedAt == null ? createdAt : builder.updatedAt;
        this.version = builder.version;
    }

    /**
     * Applies a valid lifecycle transition and advances the optimistic-lock version.
     *
     * @param next target state
     * @param now transition timestamp
     * @throws IllegalStateException when the transition is not allowed
     */
    public void transitionTo(JobStatus next, Instant now) {
        Objects.requireNonNull(next);
        if (!allowed(status, next)) {
            throw new IllegalStateException("Invalid benchmark job transition: " + status + " -> " + next);
        }
        status = next;
        updatedAt = Objects.requireNonNull(now);
        version++;
    }

    private static boolean allowed(JobStatus current, JobStatus next) {
        if (current.terminal()) return false;
        if (next == JobStatus.ERROR) return true;
        if (next == JobStatus.CANCELLED) return true;
        return switch (current) {
            case QUEUED -> next == JobStatus.RUNNING;
            case RUNNING -> next == JobStatus.ANALYZING;
            case ANALYZING -> next == JobStatus.PASSED || next == JobStatus.FAILED;
            default -> false;
        };
    }

    private static String requireText(String value, String name, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " must be non-blank and at most " + max + " characters");
        }
        return value;
    }

    /**
     * Creates an empty aggregate builder.
     *
     * @return benchmark job builder
     */
    public static Builder builder() { return new Builder(); }
    public UUID id() { return id; }
    public UUID organizationId() { return organizationId; }
    public UUID baselineVersionId() { return baselineVersionId; }
    public UUID candidateVersionId() { return candidateVersionId; }
    public UUID historicalVersionId() { return historicalVersionId; }
    public UUID datasetVersionId() { return datasetVersionId; }
    public String idempotencyKey() { return idempotencyKey; }
    public BenchmarkThresholds thresholds() { return thresholds; }
    public int warmupIterations() { return warmupIterations; }
    public int measuredIterations() { return measuredIterations; }
    public JobStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long version() { return version; }

    /**
     * Fluent builder used both for new aggregates and persistence rehydration.
     *
     * @author Shelton Bumhe
     */
    public static final class Builder {
        private UUID id;
        private UUID organizationId;
        private UUID baselineVersionId;
        private UUID candidateVersionId;
        private UUID historicalVersionId;
        private UUID datasetVersionId;
        private String idempotencyKey;
        private BenchmarkThresholds thresholds;
        private int warmupIterations;
        private int measuredIterations;
        private JobStatus status;
        private Instant createdAt;
        private Instant updatedAt;
        private long version;
        /** @param value job identifier @return this builder */
        public Builder id(UUID value) { id = value; return this; }
        /** @param value owning tenant @return this builder */
        public Builder organizationId(UUID value) { organizationId = value; return this; }
        /** @param value baseline policy version @return this builder */
        public Builder baselineVersionId(UUID value) { baselineVersionId = value; return this; }
        /** @param value candidate policy version @return this builder */
        public Builder candidateVersionId(UUID value) { candidateVersionId = value; return this; }
        /** @param value optional historical policy version @return this builder */
        public Builder historicalVersionId(UUID value) { historicalVersionId = value; return this; }
        /** @param value dataset version @return this builder */
        public Builder datasetVersionId(UUID value) { datasetVersionId = value; return this; }
        /** @param value tenant-scoped idempotency key @return this builder */
        public Builder idempotencyKey(String value) { idempotencyKey = value; return this; }
        /** @param value regression gates @return this builder */
        public Builder thresholds(BenchmarkThresholds value) { thresholds = value; return this; }
        /** @param value unmeasured iteration count @return this builder */
        public Builder warmupIterations(int value) { warmupIterations = value; return this; }
        /** @param value measured iteration count @return this builder */
        public Builder measuredIterations(int value) { measuredIterations = value; return this; }
        /** @param value current persisted state @return this builder */
        public Builder status(JobStatus value) { status = value; return this; }
        /** @param value aggregate creation timestamp @return this builder */
        public Builder createdAt(Instant value) { createdAt = value; return this; }
        /** @param value most recent transition timestamp @return this builder */
        public Builder updatedAt(Instant value) { updatedAt = value; return this; }
        /** @param value optimistic-lock version @return this builder */
        public Builder version(long value) { version = value; return this; }
        /**
         * Validates and constructs the aggregate.
         *
         * @return immutable-input benchmark job
         */
        public BenchmarkJob build() { return new BenchmarkJob(this); }
    }
}
