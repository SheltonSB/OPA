package dev.opaguard.platform.worker;

import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Best-effort Redis cache for content-identical completed benchmark executions.
 *
 * <p>Redis failures are treated as cache misses and never prevent correct job
 * execution or durable publication.</p>
 *
 * @author Shelton Bumhe
 */
@Component
public class IncrementalResultCache {
    private final StringRedisTemplate redis;

    public IncrementalResultCache(StringRedisTemplate redis) { this.redis = redis; }

    /**
     * Looks up a serialized execution by canonical fingerprint.
     *
     * @param fingerprint benchmark content fingerprint
     * @return cached payload, or empty when absent or Redis is unavailable
     */
    public Optional<String> get(String fingerprint) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key(fingerprint)));
        } catch (RedisConnectionFailureException unavailable) {
            return Optional.empty();
        }
    }

    /**
     * Caches a completed execution for 30 days on a best-effort basis.
     *
     * @param fingerprint benchmark content fingerprint
     * @param result serialized execution event
     */
    public void put(String fingerprint, String result) {
        try {
            redis.opsForValue().set(key(fingerprint), result, Duration.ofDays(30));
        } catch (RedisConnectionFailureException unavailable) {
            // Cache failure must not discard a completed benchmark.
        }
    }

    private static String key(String fingerprint) { return "opa-guard:result:v1:" + fingerprint; }
}
