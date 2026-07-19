package dev.opaguard.cli;

/**
 * A user-facing command failure with a stable exit code.
 *
 * @author Shelton Bumhe
 */
public final class CliFailure extends RuntimeException {
    private final CliExitCode exitCode;

    /**
     * Creates a command failure.
     *
     * @param exitCode stable process code
     * @param message actionable explanation
     */
    public CliFailure(CliExitCode exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    /**
     * Creates a command failure with a cause.
     *
     * @param exitCode stable process code
     * @param message actionable explanation
     * @param cause underlying failure
     */
    public CliFailure(CliExitCode exitCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }

    /**
     * Returns the stable process code.
     *
     * @return exit code
     */
    public CliExitCode exitCode() {
        return exitCode;
    }
}
