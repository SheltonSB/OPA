package dev.opaguard.exception;

/**
 * Signals an operational or validation failure that prevents a trustworthy benchmark result.
 *
 * @author Shelton Bumhe
 */
public class GuardException extends RuntimeException {
    public GuardException(String message) {
        super(message);
    }

    public GuardException(String message, Throwable cause) {
        super(message, cause);
    }
}
