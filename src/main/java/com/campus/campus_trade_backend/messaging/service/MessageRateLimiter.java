package com.campus.campus_trade_backend.messaging.service;

import com.campus.campus_trade_backend.security.RedisWindowRateLimiter;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MessageRateLimiter {
    private final Map<Long, ArrayDeque<Long>> attempts = new ConcurrentHashMap<>();
    private final Clock clock = Clock.systemUTC();
    private final StringRedisTemplate redis;
    private final boolean local;

    public MessageRateLimiter(StringRedisTemplate redis, Environment environment) {
        this.redis = redis;
        this.local = environment.acceptsProfiles(Profiles.of("local"));
    }

    @Value("${campus.messaging.max-per-minute:20}")
    private int maxPerMinute;

    public void check(long userId) {
        if (!local) {
            RedisWindowRateLimiter.check(redis, "ct:rate:message:" + userId, maxPerMinute,
                    "发送过于频繁，请稍后再试");
            return;
        }
        long now = clock.millis();
        ArrayDeque<Long> window = attempts.computeIfAbsent(userId, ignored -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst() <= now - 60_000) window.removeFirst();
            if (window.size() >= maxPerMinute) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "发送过于频繁，请稍后再试");
            }
            window.addLast(now);
        }
    }
}
