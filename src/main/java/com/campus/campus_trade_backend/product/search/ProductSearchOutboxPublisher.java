package com.campus.campus_trade_backend.product.search;

import com.campus.campus_trade_backend.order.outbox.OrderRabbitConfig;
import com.campus.campus_trade_backend.product.mapper.ProductSearchOutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = {"campus.rabbit.enabled", "campus.search.enabled"}, havingValue = "true")
public class ProductSearchOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(ProductSearchOutboxPublisher.class);
    private final ProductSearchOutboxMapper outbox;
    private final RabbitTemplate rabbit;

    public ProductSearchOutboxPublisher(ProductSearchOutboxMapper outbox, RabbitTemplate rabbit) {
        this.outbox = outbox;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${campus.search.scan-ms:2000}")
    public void dispatch() {
        for (var candidate : outbox.due(20)) {
            int attempt = candidate.attempts() + 1;
            if (outbox.claim(candidate.id(), candidate.attempts()) != 1) continue;
            try {
                CorrelationData correlation = new CorrelationData("product:" + candidate.id() + ":" + attempt);
                rabbit.convertAndSend(OrderRabbitConfig.PRODUCT_EXCHANGE, "sync",
                        String.valueOf(candidate.id()), correlation);
                var confirmation = correlation.getFuture().get(5, TimeUnit.SECONDS);
                if (!confirmation.isAck() || correlation.getReturned() != null) {
                    throw new IllegalStateException("RabbitMQ did not confirm product event");
                }
                outbox.published(candidate.id(), attempt);
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                String status = attempt >= 5 ? "DEAD" : "RETRY";
                outbox.failed(candidate.id(), attempt, status,
                        Math.min(300, 5L << Math.min(attempt, 5)));
                log.warn("Product search publish failed id={} attempt={} status={}", candidate.id(), attempt, status);
                if (failure instanceof InterruptedException) return;
            }
        }
    }
}
