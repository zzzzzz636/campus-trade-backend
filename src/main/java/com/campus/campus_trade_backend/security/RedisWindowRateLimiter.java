package com.campus.campus_trade_backend.security;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Redis 时间与 Lua 脚本保证跨实例限流判断、计数和 TTL 更新原子执行。 */
public final class RedisWindowRateLimiter {
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();

    static {
        SCRIPT.setResultType(Long.class);
        SCRIPT.setScriptText("""
                local time = redis.call('TIME')
                local now = time[1] * 1000 + math.floor(time[2] / 1000)
                redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - 60000)
                if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[1]) then return 0 end
                redis.call('ZADD', KEYS[1], now, ARGV[2])
                redis.call('PEXPIRE', KEYS[1], 60000)
                return 1
                """);
    }

    private RedisWindowRateLimiter() { }

    public static void check(StringRedisTemplate redis, String key, int limit, String blockedMessage) {
        try {
            Long allowed = redis.execute(SCRIPT, List.of(key), Integer.toString(Math.max(1, limit)), UUID.randomUUID().toString());
            if (allowed == null) throw new IllegalStateException("Redis 未返回限流结果");
            if (allowed == 0) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, blockedMessage);
        } catch (DataAccessException | IllegalStateException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "限流服务暂不可用，请稍后重试", error);
        }
    }
}
