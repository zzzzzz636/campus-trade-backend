package com.campus.campus_trade_backend.ai.agent;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** One short-lived conversation per authenticated user; Redis is authoritative outside local mode. */
@Component
public class AgentConversationStore {
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_TURNS = 6;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final boolean local;
    private final ConcurrentHashMap<Long, LocalSession> localSessions = new ConcurrentHashMap<>();

    public AgentConversationStore(StringRedisTemplate redis, ObjectMapper mapper, Environment environment) {
        this.redis = redis;
        this.mapper = mapper;
        this.local = environment.acceptsProfiles(Profiles.of("local"));
    }

    public Session open(long userId, String sessionId) {
        Session existing = read(userId);
        if (sessionId == null) return new Session(UUID.randomUUID().toString(), List.of());
        if (existing == null || !existing.id().equals(sessionId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI 会话已过期，请开始新对话");
        }
        return existing;
    }

    public void save(long userId, Session previous, String question, String answer) {
        List<Turn> turns = new ArrayList<>(previous.turns());
        turns.add(new Turn(question, answer));
        if (turns.size() > MAX_TURNS) turns = turns.subList(turns.size() - MAX_TURNS, turns.size());
        Session updated = new Session(previous.id(), List.copyOf(turns));
        if (local) {
            localSessions.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
            localSessions.put(userId, new LocalSession(updated, Instant.now().plus(TTL)));
        } else {
            try { redis.opsForValue().set(key(userId), mapper.writeValueAsString(updated), TTL); }
            catch (RuntimeException exception) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 对话暂时无法保存，请稍后重试");
            }
        }
    }

    private Session read(long userId) {
        if (local) {
            LocalSession stored = localSessions.get(userId);
            return stored == null || stored.expiresAt().isBefore(Instant.now()) ? null : stored.session();
        }
        try {
            String raw = redis.opsForValue().get(key(userId));
            if (raw == null) return null;
            JsonNode node = mapper.readTree(raw);
            List<Turn> turns = new ArrayList<>();
            for (JsonNode turn : node.path("turns")) {
                String question = turn.path("question").asString();
                String answer = turn.path("answer").asString();
                if (question.length() <= 500 && answer.length() <= 3000) turns.add(new Turn(question, answer));
            }
            return new Session(node.path("id").asString(),
                    turns.size() > MAX_TURNS ? List.copyOf(turns.subList(turns.size() - MAX_TURNS, turns.size())) : List.copyOf(turns));
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI 对话暂时无法读取，请稍后重试");
        }
    }

    private static String key(long userId) { return "ct:ai:agent:session:" + userId; }

    public record Turn(String question, String answer) { }
    public record Session(String id, List<Turn> turns) { }
    private record LocalSession(Session session, Instant expiresAt) { }
}
