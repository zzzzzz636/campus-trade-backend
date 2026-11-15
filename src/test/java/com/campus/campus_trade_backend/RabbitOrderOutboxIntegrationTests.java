package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.order.outbox.OrderRabbitConfig;
import com.campus.campus_trade_backend.order.service.OrderService;
import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "campus.rabbit.tests", matches = "true")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campustrade_rabbit_outbox;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "campus.rabbit.enabled=true", "campus.rabbit.scan-ms=500"
})
class RabbitOrderOutboxIntegrationTests {
    @Container static final GenericContainer<?> RABBIT = new GenericContainer<>(
            DockerImageName.parse("rabbitmq:4.1-management-alpine"))
            .withExposedPorts(5672)
            .withEnv("RABBITMQ_DEFAULT_USER", "campus_test")
            .withEnv("RABBITMQ_DEFAULT_PASS", "integration-test-only");

    @DynamicPropertySource static void rabbit(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "campus_test");
        registry.add("spring.rabbitmq.password", () -> "integration-test-only");
    }

    @Autowired OrderService orders;
    @Autowired ProductService products;
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;

    @Test @Timeout(90)
    void realBrokerConfirmsPublishingAndDuplicateDeliveryDoesNotDuplicateNotification() throws Exception {
        String email = UUID.randomUUID() + "@example.invalid";
        jdbc.update("INSERT INTO app_user (email, password_hash, nickname, status, created_at) "
                + "VALUES (?, 'integration-test-only', '卖家', 'ACTIVE', CURRENT_TIMESTAMP)", email);
        long seller = jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
        String buyerEmail = UUID.randomUUID() + "@example.invalid";
        jdbc.update("INSERT INTO app_user (email, password_hash, nickname, status, created_at) "
                + "VALUES (?, 'integration-test-only', '买家', 'ACTIVE', CURRENT_TIMESTAMP)", buyerEmail);
        long buyer = jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, buyerEmail);
        long product = products.create(new CreateProductRequest(1L, "Rabbit 测试商品", "校内面交",
                new BigDecimal("25.00"), ConditionLevel.GOOD), seller);
        long orderId = orders.create(product, buyer, UUID.randomUUID().toString()).id();
        long eventId = jdbc.queryForObject("SELECT id FROM order_notification_outbox WHERE order_id = ?",
                Long.class, orderId);
        await(() -> "PUBLISHED".equals(jdbc.queryForObject(
                "SELECT status FROM order_notification_outbox WHERE id = ?", String.class, eventId))
                && countNotifications(orderId) == 1, 30);
        rabbit.convertAndSend(OrderRabbitConfig.EXCHANGE, "seller", String.valueOf(eventId));
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM order_notification_consumed WHERE event_id = ?",
                Long.class, eventId) == 1, 5);
        assertEquals(1L, countNotifications(orderId));

        rabbit.convertAndSend(OrderRabbitConfig.EXCHANGE, "seller", "999999999");
        await(() -> rabbit.receive(OrderRabbitConfig.DEAD_QUEUE, 100) != null, 15);
    }

    private long countNotifications(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM app_notification WHERE reference_id = ?", Long.class, orderId);
    }

    private static void await(java.util.function.BooleanSupplier condition, int seconds) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < until) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("未在规定时间内观察到消息状态");
    }
}
