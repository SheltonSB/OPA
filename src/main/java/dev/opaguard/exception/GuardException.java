package dev.opaguard.exception;

public class GuardException extends RuntimeException {
    public GuardException(String message) {
        super(message);
    }

    public GuardException(String message, Throwable cause) {
        super(message, cause);
    }
}
