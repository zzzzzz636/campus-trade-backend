package com.campus.campus_trade_backend.order.outbox;

import com.campus.campus_trade_backend.order.mapper.OrderOutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "campus.rabbit.enabled", havingValue = "true")
public class OrderOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OrderOutboxPublisher.class);
    private final OrderOutboxMapper outbox;
    private final RabbitTemplate rabbit;

    public OrderOutboxPublisher(OrderOutboxMapper outbox, RabbitTemplate rabbit) {
        this.outbox = outbox;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${campus.rabbit.scan-ms:2000}")
    public void dispatch() {
        for (var candidate : outbox.due(20)) {
            int attempt = candidate.attempts() + 1;
            if (outbox.claim(candidate.id(), candidate.attempts()) != 1) continue;
            try {
                CorrelationData correlation = new CorrelationData(candidate.id() + ":" + attempt);
                rabbit.convertAndSend(OrderRabbitConfig.EXCHANGE, "seller",
                        String.valueOf(candidate.id()), correlation);
                var confirmation = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirmation.isAck() || correlation.getReturned() != null) {
                    throw new IllegalStateException("RabbitMQ did not confirm a routed message");
                }
                outbox.published(candidate.id(), attempt);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                String status = attempt >= 5 ? "DEAD" : "RETRY";
                outbox.failed(candidate.id(), attempt, status,
                        Math.min(300, 5L << Math.min(attempt, 5)));
                log.warn("Order outbox publish failed id={} attempt={} status={}", candidate.id(), attempt, status);
                if (failure instanceof InterruptedException) return;
            }
        }
    }
}
