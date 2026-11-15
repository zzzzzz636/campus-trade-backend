package com.campus.campus_trade_backend.behavior;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
public class BehaviorRedisStore {
    private static final long WINDOW_MS = Duration.ofHours(24).toMillis();
    private static final String RANK_KEY = "campus:behavior:hot:v1";
    private static final String ACTIVE_KEY = "campus:behavior:hot:active";
    private static final String REBUILD_KEY = "campus:behavior:hot:rebuild";
    private static final DefaultRedisScript<Long> APPLY_LIVE = new DefaultRedisScript<>("""
            local active = redis.call('GET', KEYS[1]) or 'v1'
            local rebuilding = redis.call('GET', KEYS[2])
            local function apply(generation)
              local events = 'campus:behavior:hot:events:' .. ARGV[4]
              local ranking = 'campus:behavior:hot:v1'
              if generation ~= 'v1' then
                events = 'campus:behavior:hot:events:' .. generation .. ':' .. ARGV[4]
                ranking = 'campus:behavior:hot:rank:' .. generation
              end
              local added = redis.call('ZADD', events, 'NX', ARGV[1], ARGV[2])
              redis.call('ZREMRANGEBYSCORE', events, '-inf', ARGV[3])
              local count = redis.call('ZCARD', events)
              redis.call('EXPIRE', events, 90000)
              if count > 0 then redis.call('ZADD', ranking, count, ARGV[4])
              else redis.call('ZREM', ranking, ARGV[4]) end
              redis.call('EXPIRE', ranking, 90000)
              return added
            end
            local added = apply(active)
            if rebuilding and rebuilding ~= active then apply(rebuilding) end
            return added
            """, Long.class);
    private static final DefaultRedisScript<Long> APPLY_HEAT = new DefaultRedisScript<>("""
            local added = redis.call('ZADD', KEYS[1], 'NX', ARGV[1], ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[3])
            local count = redis.call('ZCARD', KEYS[1])
            redis.call('EXPIRE', KEYS[1], 90000)
            if count > 0 then
              redis.call('ZADD', KEYS[2], count, ARGV[4])
            else
              redis.call('ZREM', KEYS[2], ARGV[4])
            end
            redis.call('EXPIRE', KEYS[2], 90000)
            return added
            """, Long.class);
    private static final DefaultRedisScript<Long> BEGIN_REBUILD = new DefaultRedisScript<>("""
            if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', 600) then return 1 end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> RENEW_REBUILD = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('EXPIRE', KEYS[1], 600)
              return 1
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> FINISH_REBUILD = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('SET', KEYS[2], ARGV[1])
              redis.call('DEL', KEYS[1])
              return 1
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> ABORT_REBUILD = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> REFRESH_HEAT = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            local count = redis.call('ZCARD', KEYS[1])
            if count > 0 then
              redis.call('ZADD', KEYS[2], count, ARGV[2])
            else
              redis.call('ZREM', KEYS[2], ARGV[2])
            end
            return count
            """, Long.class);
    private static final DefaultRedisScript<Long> APPLY_STATS = new DefaultRedisScript<>("""
            if redis.call('SET', KEYS[1], '1', 'NX', 'EX', 259200) then
              redis.call('HINCRBY', KEYS[2], ARGV[1], 1)
              redis.call('EXPIRE', KEYS[2], 345600)
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public BehaviorRedisStore(StringRedisTemplate redis) { this.redis = redis; }

    public boolean applyHeat(BehaviorEvent event, Instant now) {
        if (event.occurredAt().toEpochMilli() <= now.toEpochMilli() - WINDOW_MS) return false;
        String id = String.valueOf(event.productId());
        return Objects.equals(1L, redis.execute(APPLY_LIVE,
                List.of(ACTIVE_KEY, REBUILD_KEY),
                String.valueOf(event.occurredAt().toEpochMilli()), event.eventId(),
                String.valueOf(now.toEpochMilli() - WINDOW_MS), id));
    }

    public boolean beginRebuild(String generation) {
        return Objects.equals(1L, redis.execute(BEGIN_REBUILD, List.of(REBUILD_KEY), generation));
    }

    public boolean renewRebuild(String generation) {
        return Objects.equals(1L, redis.execute(RENEW_REBUILD, List.of(REBUILD_KEY), generation));
    }

    public void replay(BehaviorEvent event, String generation, Instant now) {
        if (event.occurredAt().toEpochMilli() <= now.toEpochMilli() - WINDOW_MS) return;
        String id = String.valueOf(event.productId());
        redis.execute(APPLY_HEAT, List.of(eventsKey(generation, id), rankKey(generation)),
                String.valueOf(event.occurredAt().toEpochMilli()), event.eventId(),
                String.valueOf(now.toEpochMilli() - WINDOW_MS), id);
    }

    public boolean finishRebuild(String generation) {
        return Objects.equals(1L, redis.execute(FINISH_REBUILD,
                List.of(REBUILD_KEY, ACTIVE_KEY), generation));
    }

    public void abortRebuild(String generation) {
        redis.execute(ABORT_REBUILD, List.of(REBUILD_KEY), generation);
    }

    public String activeGeneration() {
        String value = redis.opsForValue().get(ACTIVE_KEY);
        return value == null ? "v1" : value;
    }

    public boolean applyStats(BehaviorEvent event) {
        String day = event.occurredAt().toString().substring(0, 10);
        return Objects.equals(1L, redis.execute(APPLY_STATS,
                List.of("campus:behavior:stats:seen:" + event.eventId(),
                        "campus:behavior:stats:" + day), event.type()));
    }

    public List<HotProduct> top(int size, Instant now) {
        // 查询时清理所有参与排行的商品，保证 24 小时窗口准确；当前为小规模校园站点方案。
        String generation = activeGeneration();
        String rank = rankKey(generation);
        Set<String> ids = redis.opsForZSet().range(rank, 0, -1);
        if (ids == null || ids.isEmpty()) return List.of();
        String cutoff = String.valueOf(now.toEpochMilli() - WINDOW_MS);
        for (String id : ids) redis.execute(REFRESH_HEAT, List.of(eventsKey(generation, id), rank), cutoff, id);
        Set<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>> ranked =
                redis.opsForZSet().reverseRangeWithScores(rank, 0, size - 1);
        if (ranked == null) return List.of();
        List<HotProduct> result = new ArrayList<>();
        for (var item : ranked) {
            if (item.getValue() != null && item.getScore() != null) {
                result.add(new HotProduct(Long.parseLong(item.getValue()), item.getScore().longValue()));
            }
        }
        return result;
    }

    private static String eventsKey(String generation, String id) {
        return "v1".equals(generation) ? "campus:behavior:hot:events:" + id
                : "campus:behavior:hot:events:" + generation + ":" + id;
    }

    private static String rankKey(String generation) {
        return "v1".equals(generation) ? RANK_KEY : "campus:behavior:hot:rank:" + generation;
    }

    public record HotProduct(long productId, long score) { }
}
