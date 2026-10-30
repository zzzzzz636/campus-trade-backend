package com.campus.campus_trade_backend.auth.verification;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;

@Component
@Profile("!local")
public class RedisVerificationCodeStore implements VerificationCodeStore {
    private static final Duration VALID_FOR = Duration.ofMinutes(5);
    private static final Duration SEND_COOLDOWN = Duration.ofMinutes(1);
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>();

    static {
        CONSUME_SCRIPT.setResultType(Long.class);
        CONSUME_SCRIPT.setScriptText("""
                local current = redis.call('GET', KEYS[1])
                if not current then return 0 end
                if current == ARGV[1] then
                  redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
                  return 1
                end
                local attempts = redis.call('INCR', KEYS[2])
                if attempts == 1 then
                  local ttl = redis.call('PTTL', KEYS[1])
                  if ttl > 0 then redis.call('PEXPIRE', KEYS[2], ttl) end
                end
                if attempts >= 5 then
                  redis.call('DEL', KEYS[1])
                  return -2
                end
                return -1
                """);
    }

    private final StringRedisTemplate redis;

    public RedisVerificationCodeStore(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public void save(String emailKey, String codeDigest) {
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(cooldownKey(emailKey), "1", SEND_COOLDOWN))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "请一分钟后再获取验证码");
        }
        redis.opsForValue().set(codeKey(emailKey), codeDigest, VALID_FOR);
        redis.delete(attemptsKey(emailKey));
    }

    @Override
    public Result consume(String emailKey, String codeDigest) {
        Long result = redis.execute(CONSUME_SCRIPT,
                List.of(codeKey(emailKey), attemptsKey(emailKey), cooldownKey(emailKey)), codeDigest);
        if (result == null) throw new IllegalStateException("Redis 未返回验证码校验结果");
        return switch (result.intValue()) {
            case 1 -> Result.VALID;
            case 0 -> Result.MISSING;
            case -1 -> Result.INVALID;
            case -2 -> Result.LOCKED;
            default -> throw new IllegalStateException("未知验证码校验结果");
        };
    }

    @Override
    public void clear(String emailKey) {
        redis.delete(List.of(codeKey(emailKey), attemptsKey(emailKey), cooldownKey(emailKey)));
    }

    private static String codeKey(String key) { return "ct:otp:code:" + key; }
    private static String attemptsKey(String key) { return "ct:otp:attempts:" + key; }
    private static String cooldownKey(String key) { return "ct:otp:cooldown:" + key; }
}
