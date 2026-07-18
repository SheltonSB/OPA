package dev.opaguard.platform.security;

import dev.opaguard.exception.GuardException;
import dev.opaguard.platform.worker.IncrementalResultCache;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisFailureBehaviorTest {
    @Test
    void admissionFailsClosedWhenRedisIsUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));

        var limiter = new RedisRateLimiter(redis, Clock.systemUTC());
        assertThatThrownBy(() -> limiter.requirePermit(UUID.randomUUID(), 60))
                .isInstanceOf(GuardException.class).hasMessageContaining("unavailable");
    }

    @Test
    void incrementalCacheFailsOpenWhenRedisIsUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(any())).thenThrow(new RedisConnectionFailureException("redis unavailable"));

        assertThat(new IncrementalResultCache(redis).get("a".repeat(64))).isEmpty();
    }
}
