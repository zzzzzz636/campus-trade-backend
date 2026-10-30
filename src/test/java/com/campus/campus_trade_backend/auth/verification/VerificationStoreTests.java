package com.campus.campus_trade_backend.auth.verification;

import com.campus.campus_trade_backend.auth.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class VerificationStoreTests {
    @Test
    void expiresAfterFiveMinutesAndCannotBeReused() {
        MutableClock clock = new MutableClock();
        LocalVerificationCodeStore store = new LocalVerificationCodeStore(clock);
        store.save("email-1", "digest-a");
        clock.advance(Duration.ofMinutes(5));
        assertEquals(VerificationCodeStore.Result.MISSING, store.consume("email-1", "digest-a"));

        store.save("email-1", "digest-b");
        assertEquals(VerificationCodeStore.Result.VALID, store.consume("email-1", "digest-b"));
        assertEquals(VerificationCodeStore.Result.MISSING, store.consume("email-1", "digest-b"));
    }

    @Test
    void mailFailureDoesNotLeaveAUsableCodeOrCooldown() {
        LocalVerificationCodeStore store = new LocalVerificationCodeStore();
        VerificationMailSender brokenMail = (email, code) -> {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SMTP 不可用");
        };
        VerificationService service = new VerificationService(store, brokenMail, mock(UserMapper.class), testKey());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> service.send("test@example.invalid")).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> service.send("test@example.invalid")).getStatusCode());
    }

    @Test
    void unavailableRedisReturnsServiceUnavailable() {
        VerificationCodeStore unavailable = new VerificationCodeStore() {
            @Override public void save(String emailKey, String digest) {
                throw new DataAccessResourceFailureException("Redis offline");
            }
            @Override public Result consume(String emailKey, String digest) {
                throw new DataAccessResourceFailureException("Redis offline");
            }
            @Override public void clear(String emailKey) { }
        };
        VerificationService service = new VerificationService(unavailable, (email, code) -> "SMTP",
                mock(UserMapper.class), testKey());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> service.send("test@example.invalid")).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertThrows(ResponseStatusException.class,
                () -> service.verifyAndConsume("test@example.invalid", "123456")).getStatusCode());
    }

    private static SecretKeySpec testKey() { return new SecretKeySpec(new byte[32], "HmacSHA256"); }

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-28T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
