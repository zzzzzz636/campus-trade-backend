package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.order.dto.CreateOrderRequest;
import com.campus.campus_trade_backend.order.dto.FulfillmentType;
import com.campus.campus_trade_backend.order.outbox.OrderNotificationConsumer;
import com.campus.campus_trade_backend.order.service.OrderService;
import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campustrade_outbox;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "campus.rabbit.enabled=true", "spring.rabbitmq.listener.simple.auto-startup=false",
        "campus.rabbit.scan-ms=9999999"
})
class OrderOutboxIntegrationTests {
    @Autowired OrderService orders;
    @Autowired ProductService products;
    @Autowired OrderNotificationConsumer consumer;
    @Autowired JdbcTemplate jdbc;

    @Test
    void orderAndOutboxCommitTogetherAndDuplicateDeliveryIsHarmless() {
        long seller = user();
        long buyer = user();
        long product = product(seller);
        long orderId = orders.create(product, buyer, UUID.randomUUID().toString()).id();
        long eventId = jdbc.queryForObject("SELECT id FROM order_notification_outbox WHERE order_id = ?",
                Long.class, orderId);
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM app_notification WHERE reference_id = ?",
                Long.class, orderId));
        consumer.consume(String.valueOf(eventId));
        consumer.consume(String.valueOf(eventId));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM app_notification WHERE reference_id = ?",
                Long.class, orderId));
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM order_notification_consumed WHERE event_id = ?",
                Long.class, eventId));
    }

    @Test
    void failedOrderRollsBackReservationAndOutbox() {
        long seller = user();
        long buyer = user();
        long product = product(seller);
        long outboxBefore = jdbc.queryForObject("SELECT COUNT(*) FROM order_notification_outbox", Long.class);
        assertThrows(ResponseStatusException.class, () -> orders.create(new CreateOrderRequest(
                product, UUID.randomUUID().toString(), FulfillmentType.SHIPPING,
                null, null, null, null, null), buyer));
        assertEquals("ON_SALE", jdbc.queryForObject("SELECT status FROM product WHERE id = ?",
                String.class, product));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE product_id = ?",
                Long.class, product));
        assertEquals(outboxBefore, jdbc.queryForObject("SELECT COUNT(*) FROM order_notification_outbox", Long.class));
    }

    private long user() {
        String email = UUID.randomUUID() + "@example.invalid";
        jdbc.update("INSERT INTO app_user (email, password_hash, nickname, status, created_at) "
                + "VALUES (?, 'integration-test-only', '测试用户', 'ACTIVE', CURRENT_TIMESTAMP)", email);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    }

    private long product(long seller) {
        return products.create(new CreateProductRequest(1L, "outbox 测试商品", "校内面交",
                new BigDecimal("39.00"), ConditionLevel.GOOD), seller);
    }
}
