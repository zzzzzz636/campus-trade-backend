package com.campus.campus_trade_backend.ai.service;

import com.campus.campus_trade_backend.ai.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiRequestRateLimiterTests {
    @Test
    void limitsEachUserIndependentlyWithinTheMinute() {
        AiProperties properties = new AiProperties();
        properties.setRateLimitPerMinute(2);
        AiRequestRateLimiter limiter = new AiRequestRateLimiter(properties,
                Clock.fixed(Instant.parse("2026-10-30T14:00:30Z"), ZoneOffset.UTC));

        limiter.check(10L);
        limiter.check(10L);
        limiter.check(20L);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> limiter.check(10L));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, exception.getStatusCode());
    }
}
