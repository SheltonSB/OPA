package dev.opaguard.platform.messaging;

/**
 * Versioned Kafka topic names forming the public asynchronous platform contract.
 *
 * @author Shelton Bumhe
 */
public final class KafkaTopics {
    /** Benchmark job requests consumed by the worker group. */
    public static final String JOB_REQUESTED_V1 = "opa.guard.benchmark-job-requested.v1";
    /** Completed benchmark executions consumed by analyzers. */
    public static final String SHARD_COMPLETED_V1 = "opa.guard.benchmark-shard-completed.v1";
    /** Terminal analyses consumed by CI and source-control integrations. */
    public static final String ANALYSIS_COMPLETED_V1 = "opa.guard.analysis-completed.v1";
    /** Poison events retained for diagnosis and controlled replay. */
    public static final String DEAD_LETTER_V1 = "opa.guard.dead-letter.v1";

    private KafkaTopics() {}
}
