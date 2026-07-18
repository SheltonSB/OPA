package dev.opaguard.platform.security;

import dev.opaguard.exception.GuardException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Enforces a tenant-scoped fixed-window admission limit with an atomic Redis script.
 *
 * <p>The limiter fails closed when Redis is unavailable so an infrastructure
 * outage cannot turn into an unbounded benchmark workload.</p>
 *
 * @author Shelton Bumhe
 */
@Component
@ConditionalOnProperty(name = "opa-guard.mode", havingValue = "coordinator")
public class RedisRateLimiter {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end
            return current
            """, Long.class);
    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisRateLimiter(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /**
     * Requires capacity in the current organization-minute window.
     *
     * @param organizationId tenant used to partition counters
     * @param limitPerMinute maximum admitted requests in one minute
     * @throws RateLimitExceededException when the tenant has exhausted its quota
     * @throws GuardException when limiter state cannot be established
     */
    public void requirePermit(UUID organizationId, int limitPerMinute) {
        long minute = clock.instant().getEpochSecond() / 60;
        String key = "opa-guard:rate:" + organizationId + ":" + minute;
        try {
            Long count = redis.execute(SCRIPT, List.of(key), "120");
            if (count == null) throw new GuardException("Rate limit state unavailable");
            if (count > limitPerMinute) throw new RateLimitExceededException();
        } catch (RedisConnectionFailureException exception) {
            throw new GuardException("Rate limit service unavailable", exception);
        }
    }

    /**
     * Signals that a valid request exceeded its tenant admission quota.
     *
     * @author Shelton Bumhe
     */
    public static final class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException() { super("Organization benchmark submission limit exceeded"); }
    }
}
