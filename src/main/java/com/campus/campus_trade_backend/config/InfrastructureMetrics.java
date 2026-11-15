package com.campus.campus_trade_backend.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import com.campus.campus_trade_backend.order.outbox.OrderRabbitConfig;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class InfrastructureMetrics {
    private final JdbcTemplate jdbc;
    private final RedisConnectionFactory redis;
    private final AmqpAdmin rabbit;

    public InfrastructureMetrics(JdbcTemplate jdbc, RedisConnectionFactory redis, MeterRegistry registry,
                                 ObjectProvider<AmqpAdmin> rabbit,
                                 @Value("${campus.rabbit.enabled:false}") boolean rabbitEnabled) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.rabbit = rabbitEnabled ? rabbit.getIfAvailable() : null;
        for (String kind : new String[]{"order", "product"}) {
            for (String state : new String[]{"pending", "retry", "dead"}) {
                Gauge.builder("campus.outbox.events", this,
                                monitor -> monitor.outboxCount(kind, state))
                        .tag("kind", kind).tag("state", state).register(registry);
            }
        }
        Gauge.builder("campus.redis.up", this, InfrastructureMetrics::redisUp).register(registry);
        if (rabbitEnabled) {
            for (String queue : new String[]{OrderRabbitConfig.QUEUE, OrderRabbitConfig.DEAD_QUEUE,
                    OrderRabbitConfig.PRODUCT_QUEUE, OrderRabbitConfig.PRODUCT_DEAD_QUEUE}) {
                Gauge.builder("campus.rabbit.queue.messages", this,
                                monitor -> monitor.queueCount(queue))
                        .tag("queue", queue).register(registry);
            }
        }
    }

    private double outboxCount(String kind, String state) {
        String table = "order".equals(kind) ? "order_notification_outbox" : "product_search_outbox";
        String status = "dead".equals(state) ? "status = 'DEAD'"
                : "retry".equals(state) ? "status = 'RETRY'"
                : "status IN ('PENDING','RETRY','SENDING')";
        try {
            Number count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + status, Number.class);
            return count == null ? 0 : count.doubleValue();
        } catch (RuntimeException unavailable) {
            return -1; // 区分数据库不可用与队列为空，不在指标标签中包含异常或敏感数据。
        }
    }

    private double redisUp() {
        try (var connection = redis.getConnection()) {
            return "PONG".equalsIgnoreCase(connection.ping()) ? 1 : 0;
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }

    private double queueCount(String name) {
        if (rabbit == null) return -1;
        try {
            var info = rabbit.getQueueInfo(name);
            return info == null ? -1 : info.getMessageCount();
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }
}
