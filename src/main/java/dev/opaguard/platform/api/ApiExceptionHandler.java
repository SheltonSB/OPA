package dev.opaguard.platform.api;

import dev.opaguard.exception.GuardException;
import dev.opaguard.platform.security.RedisRateLimiter;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.UUID;

/**
 * Maps platform failures to sanitized RFC 9457 problem details.
 *
 * <p>Internal exception messages are not returned for infrastructure failures;
 * clients receive an incident identifier while operators receive a safe log event.</p>
 *
 * @author Shelton Bumhe
 */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BenchmarkJobController.JobNotFoundException.class)
    ProblemDetail notFound(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class, IllegalArgumentException.class})
    ProblemDetail invalid(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "One or more request values are invalid");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden() {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", "The authenticated principal cannot access this tenant");
    }

    @ExceptionHandler(RedisRateLimiter.RateLimitExceededException.class)
    ProblemDetail rateLimited() {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded", "Retry after the current rate-limit window");
    }

    @ExceptionHandler(GuardException.class)
    ProblemDetail unavailable(GuardException exception) {
        String incidentId = UUID.randomUUID().toString();
        LOG.error("Platform operation failed; incidentId={}; type={}", incidentId, exception.getClass().getSimpleName());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable", "Operation failed; incident=" + incidentId);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("urn:opa-guard:error:" + status.value()));
        return problem;
    }
}
