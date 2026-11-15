package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.order.service.OrderService;
import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campustrade_order_idempotency;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
class OrderIdempotencyIntegrationTests {
    @Autowired OrderService orders;
    @Autowired ProductService products;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Timeout(30)
    void concurrentRetriesShareOneOrderAndRejectedKeyReuseRollsBackReservation() throws Exception {
        long seller = user();
        long buyer = user();
        long product = product(seller);
        String key = UUID.randomUUID().toString();
        var pool = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return orders.create(product, buyer, key).id();
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            long id = futures.get(0).get(15, TimeUnit.SECONDS);
            for (Future<Long> future : futures) assertEquals(id, future.get(15, TimeUnit.SECONDS));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE product_id = ?",
                    Long.class, product));
            assertEquals("RESERVED", jdbc.queryForObject("SELECT status FROM product WHERE id = ?",
                    String.class, product));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }

        long secondProduct = product(seller);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> orders.create(secondProduct, buyer, key));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("ON_SALE", jdbc.queryForObject("SELECT status FROM product WHERE id = ?",
                String.class, secondProduct));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE product_id = ?",
                Long.class, secondProduct));
    }

    private long user() {
        String email = UUID.randomUUID() + "@example.invalid";
        jdbc.update("INSERT INTO app_user (email, password_hash, nickname, status, created_at) "
                + "VALUES (?, 'integration-test-only', '测试用户', 'ACTIVE', CURRENT_TIMESTAMP)", email);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    }

    private long product(long seller) {
        return products.create(new CreateProductRequest(1L, "幂等测试商品", "校内面交",
                new BigDecimal("39.00"), ConditionLevel.GOOD), seller);
    }
}
