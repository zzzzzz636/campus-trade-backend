package com.campus.campus_trade_backend.order.outbox;

import com.campus.campus_trade_backend.order.mapper.OrderOutboxMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderOutboxPublisherTests {
    @Test
    void confirmedPublishIsMarkedPublishedOnlyAfterAck() {
        OrderOutboxMapper mapper = mock(OrderOutboxMapper.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        when(mapper.due(20)).thenReturn(List.of(new OrderOutboxMapper.OutboxCandidate(43, 0)));
        when(mapper.claim(43L, 0)).thenReturn(1);
        doAnswer(invocation -> {
            CorrelationData data = invocation.getArgument(3);
            data.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).convertAndSend(eq(OrderRabbitConfig.EXCHANGE), eq("seller"), eq("43"),
                any(CorrelationData.class));
        new OrderOutboxPublisher(mapper, rabbit).dispatch();
        verify(mapper).published(43, 1);
        verify(mapper, never()).failed(anyLong(), anyInt(), anyString(), anyLong());
    }

    @Test
    void failedPublishRemainsRetryableInsteadOfBeingMarkedPublished() {
        OrderOutboxMapper mapper = mock(OrderOutboxMapper.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        when(mapper.due(20)).thenReturn(List.of(new OrderOutboxMapper.OutboxCandidate(42, 0)));
        when(mapper.claim(42L, 0)).thenReturn(1);
        doThrow(new IllegalStateException("broker unavailable")).when(rabbit)
                .convertAndSend(eq(OrderRabbitConfig.EXCHANGE), eq("seller"), eq("42"), any(CorrelationData.class));
        new OrderOutboxPublisher(mapper, rabbit).dispatch();
        verify(mapper).failed(42L, 1, "RETRY", 10L);
        verify(mapper, never()).published(anyLong(), anyInt());
    }
}
