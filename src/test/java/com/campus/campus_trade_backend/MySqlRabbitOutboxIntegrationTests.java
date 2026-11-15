package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.order.mapper.OrderOutboxMapper;
import com.campus.campus_trade_backend.order.outbox.OrderOutboxPublisher;
import com.campus.campus_trade_backend.order.outbox.OrderRabbitConfig;
import com.campus.campus_trade_backend.order.service.OrderService;
import com.campus.campus_trade_backend.order.dto.CreateOrderRequest;
import com.campus.campus_trade_backend.order.dto.FulfillmentType;
import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "campus.rabbit.tests", matches = "true")
@ActiveProfiles("mysql")
@SpringBootTest(properties = {
        "CAMPUS_DB_PASSWORD=integration-test-only",
        "CAMPUS_JWT_SECRET=QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=",
        "campus.rabbit.enabled=true", "campus.rabbit.scan-ms=600000",
        "spring.data.redis.url=redis://127.0.0.1:6379"
})
class MySqlRabbitOutboxIntegrationTests {
    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("campus_trade").withUsername("campus_trade")
            .withPassword("integration-test-only");
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

    @Autowired ProductService products;
    @Autowired OrderService orders;
    @Autowired OrderOutboxMapper outbox;
    @Autowired OrderOutboxPublisher publisher;
    @Autowired RabbitTemplate rabbit;
    @Autowired JdbcTemplate jdbc;

    @Test @Timeout(120)
    void leaseRecoveryDuplicateDeliveryAndDeadLetterUseRealMysqlAndRabbit() throws Exception {
        long seller = user();
        long buyer = user();
        long product = products.create(new CreateProductRequest(1L, "真实 MySQL Rabbit 测试商品",
                "校园面交", new BigDecimal("30.00"), ConditionLevel.GOOD), seller);
        long orderId = orders.create(product, buyer, UUID.randomUUID().toString()).id();
        long eventId = jdbc.queryForObject("SELECT id FROM order_notification_outbox WHERE order_id = ?",
                Long.class, orderId);

        // 模拟进程在抢占租约后崩溃：过期 SENDING 必须可被另一个发布者重新领取。
        assertEquals(1, outbox.claim(eventId, 0));
        jdbc.update("UPDATE order_notification_outbox SET next_attempt_at = "
                + "DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND) WHERE id = ?", eventId);
        publisher.dispatch();
        assertEquals("PUBLISHED", status(eventId), "发布失败，attempts=" + outbox.find(eventId).attempts());
        await(() -> notifications(orderId) == 1, 20);
        assertEquals(2, outbox.find(eventId).attempts());

        rabbit.convertAndSend(OrderRabbitConfig.EXCHANGE, "seller", String.valueOf(eventId));
        await(() -> consumed(eventId) == 1, 10);
        assertEquals(1, notifications(orderId));

        rabbit.convertAndSend(OrderRabbitConfig.EXCHANGE, "seller", "999999999");
        await(() -> rabbit.receive(OrderRabbitConfig.DEAD_QUEUE, 100) != null, 20);
    }

    @Test @Timeout(120)
    void brokerOutageLeavesEventRetryableAndDeliveryRecovers() throws Exception {
        long seller = user();
        long buyer = user();
        long product = products.create(new CreateProductRequest(1L, "Broker 故障测试商品",
                "校园面交", new BigDecimal("31.00"), ConditionLevel.GOOD), seller);
        long orderId = orders.create(product, buyer, UUID.randomUUID().toString()).id();
        long eventId = jdbc.queryForObject("SELECT id FROM order_notification_outbox WHERE order_id = ?",
                Long.class, orderId);
        RABBIT.execInContainer("rabbitmqctl", "stop_app");
        try {
            publisher.dispatch();
            assertEquals("RETRY", status(eventId));
            assertEquals(0, notifications(orderId));
        } finally {
            RABBIT.execInContainer("rabbitmqctl", "start_app");
        }
        jdbc.update("UPDATE order_notification_outbox SET next_attempt_at = "
                + "DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND) WHERE id = ?", eventId);
        publisher.dispatch();
        await(() -> "PUBLISHED".equals(status(eventId)) && notifications(orderId) == 1, 30);
        assertEquals(2, outbox.find(eventId).attempts());
    }

    @Test
    void productChangeEventCommitsAndRollsBackWithBusinessTransaction() {
        long seller = user();
        long buyer = user();
        long product = products.create(new CreateProductRequest(1L, "事件事务测试商品",
                "校园面交", new BigDecimal("32.00"), ConditionLevel.GOOD), seller);
        assertEquals(1L, productEvents(product));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> orders.create(new CreateOrderRequest(product, UUID.randomUUID().toString(),
                        FulfillmentType.SHIPPING, null, null, null, null, null), buyer));
        assertEquals("ON_SALE", jdbc.queryForObject("SELECT status FROM product WHERE id = ?", String.class, product));
        assertEquals(1L, productEvents(product));
        orders.create(product, buyer, UUID.randomUUID().toString());
        assertEquals(2L, productEvents(product));
        assertEquals("RESERVED", jdbc.queryForObject("SELECT status FROM product WHERE id = ?", String.class, product));
    }

    private long productEvents(long productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_search_outbox WHERE product_id = ?",
                Long.class, productId);
    }

    private long user() {
        String email = UUID.randomUUID() + "@example.invalid";
        jdbc.update("INSERT INTO app_user (email, password_hash, nickname, status, created_at) "
                + "VALUES (?, 'integration-test-only', '用户', 'ACTIVE', CURRENT_TIMESTAMP)", email);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM order_notification_outbox WHERE id = ?", String.class, id);
    }
    private long notifications(long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM app_notification WHERE reference_id = ?",
                Long.class, orderId);
    }
    private long consumed(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_notification_consumed WHERE event_id = ?",
                Long.class, id);
    }
    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < until) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("消息状态未在规定时间内达到预期");
    }
}
