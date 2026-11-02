package com.campus.campus_trade_backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisWindowRateLimiterTests {
    @Test
    void rejectsWhenAtomicScriptReportsLimit() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), any(List.class), any(String.class), any(String.class)))
                .thenReturn(1L, 0L);
        RedisWindowRateLimiter.check(redis, "ct:rate:test:1", 1, "过于频繁");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(ResponseStatusException.class,
                () -> RedisWindowRateLimiter.check(redis, "ct:rate:test:1", 1, "过于频繁")).getStatusCode());
    }

    @Test
    void failsClosedWhenRedisGivesNoResult() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> RedisWindowRateLimiter.check(redis, "ct:rate:test:2", 1, "过于频繁")).getStatusCode());
    }
}
