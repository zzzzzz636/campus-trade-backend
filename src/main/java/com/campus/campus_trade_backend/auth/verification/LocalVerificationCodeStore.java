package com.campus.campus_trade_backend.auth.verification;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Component
@Profile("local")
public class LocalVerificationCodeStore implements VerificationCodeStore {
    private static final Duration VALID_FOR = Duration.ofMinutes(5);
    private static final Duration SEND_COOLDOWN = Duration.ofMinutes(1);
    private final Clock clock;
    private final Map<String, Entry> codes = new HashMap<>();
    private final Map<String, Instant> cooldowns = new HashMap<>();

    public LocalVerificationCodeStore() { this(Clock.systemUTC()); }

    LocalVerificationCodeStore(Clock clock) { this.clock = clock; }

    @Override
    public synchronized void save(String emailKey, String codeDigest) {
        Instant now = clock.instant();
        cooldowns.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
        if (cooldowns.containsKey(emailKey)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请一分钟后再获取验证码");
        }
        codes.put(emailKey, new Entry(codeDigest, now.plus(VALID_FOR)));
        cooldowns.put(emailKey, now.plus(SEND_COOLDOWN));
    }

    @Override
    public synchronized Result consume(String emailKey, String codeDigest) {
        Entry entry = codes.get(emailKey);
        if (entry == null || !entry.expiresAt.isAfter(clock.instant())) {
            codes.remove(emailKey);
            return Result.MISSING;
        }
        if (MessageDigest.isEqual(entry.digest.getBytes(StandardCharsets.US_ASCII),
                codeDigest.getBytes(StandardCharsets.US_ASCII))) {
            clear(emailKey);
            return Result.VALID;
        }
        entry.attempts++;
        if (entry.attempts >= 5) {
            codes.remove(emailKey);
            return Result.LOCKED;
        }
        return Result.INVALID;
    }

    @Override
    public synchronized void clear(String emailKey) {
        codes.remove(emailKey);
        cooldowns.remove(emailKey);
    }

    private static class Entry {
        private final String digest;
        private final Instant expiresAt;
        private int attempts;

        private Entry(String digest, Instant expiresAt) {
            this.digest = digest;
            this.expiresAt = expiresAt;
        }
    }
}
