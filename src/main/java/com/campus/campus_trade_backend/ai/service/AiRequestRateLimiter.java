package com.campus.campus_trade_backend.ai.service;

import com.campus.campus_trade_backend.ai.config.AiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AiRequestRateLimiter {
    private final AiProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public AiRequestRateLimiter(AiProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AiRequestRateLimiter(AiProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void check(long userId) {
        int limit = Math.max(1, properties.getRateLimitPerMinute());
        long currentMinute = clock.instant().getEpochSecond() / 60;
        boolean[] allowed = {false};
        windows.compute(userId, (ignored, previous) -> {
            Window next = previous == null || previous.minute() != currentMinute
                    ? new Window(currentMinute, 1)
                    : new Window(currentMinute, previous.count() + 1);
            allowed[0] = next.count() <= limit;
            return next;
        });
        if (!allowed[0]) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "AI 辅助请求过于频繁，请一分钟后再试");
        }
    }

    private record Window(long minute, int count) { }
}
