package com.campus.campus_trade_backend.ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentConversationStoreTests {
    @Test
    void redisUnavailableFailsClosedRatherThanSilentlyLosingConversation() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(any())).thenThrow(new IllegalStateException("Redis unavailable"));
        doThrow(new IllegalStateException("Redis unavailable")).when(values).set(any(), any(), any(Duration.class));
        AgentConversationStore store = new AgentConversationStore(redis, new ObjectMapper(), mock(Environment.class));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ResponseStatusException.class, () -> store.open(7, "old-session")).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                assertThrows(ResponseStatusException.class,
                        () -> store.save(7, new AgentConversationStore.Session("id", List.of()), "问", "答"))
                        .getStatusCode());
    }
}
