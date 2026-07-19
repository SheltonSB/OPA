package dev.opaguard.cli;

/**
 * Stable exit codes exposed by the developer-facing command line interface.
 *
 * @author Shelton Bumhe
 */
public enum CliExitCode {
    PASS(0),
    DECISION_MISMATCH(10),
    LATENCY_REGRESSION(11),
    MEMORY_REGRESSION(12),
    MULTIPLE_GUARD_FAILURES(13),
    INVALID_CONFIGURATION(20),
    GIT_RESOLUTION_FAILURE(21),
    MISSING_INPUT(22),
    INVALID_POLICY(23),
    OPA_EXECUTION_FAILURE(30),
    BENCHMARK_TIMEOUT(31),
    INTERNAL_ERROR(40);

    private final int value;

    CliExitCode(int value) {
        this.value = value;
    }

    /**
     * Returns the process exit code.
     *
     * @return stable numeric exit code
     */
    public int value() {
        return value;
    }
}
