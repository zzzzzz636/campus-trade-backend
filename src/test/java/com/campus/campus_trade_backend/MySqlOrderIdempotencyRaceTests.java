package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.order.service.OrderService;
import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "campus.mysql.tests", matches = "true")
@ActiveProfiles("mysql")
@SpringBootTest(properties = {
        "CAMPUS_DB_PASSWORD=integration-test-only",
        "CAMPUS_JWT_SECRET=QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=",
        "spring.flyway.baseline-on-migrate=false",
        "spring.data.redis.url=redis://127.0.0.1:6379",
        "spring.mail.host=127.0.0.1", "spring.mail.port=1025",
        "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "campus.mail.from=noreply@campustrade.local"
})
class MySqlOrderIdempotencyRaceTests {
    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("campus_trade").withUsername("campus_trade")
            .withPassword("integration-test-only");

    @Autowired OrderService orders;
    @Autowired ProductService products;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Timeout(45)
    void concurrentRetriesOfSameRequestKeyReturnOneOrderId() throws Exception {
        long seller = user();
        long buyer = user();
        long product = products.create(new CreateProductRequest(1L, "并发幂等商品", "校内面交",
                new BigDecimal("39.00"), ConditionLevel.GOOD), seller);
        String key = UUID.randomUUID().toString();
        var pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                return orders.create(product, buyer, key).id();
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            long id = futures.get(0).get(30, TimeUnit.SECONDS);
            for (Future<Long> future : futures) assertEquals(id, future.get(30, TimeUnit.SECONDS));
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE product_id = ?",
                    Long.class, product));
            assertEquals("RESERVED", jdbc.queryForObject("SELECT status FROM product WHERE id = ?",
                    String.class, product));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private long user() {
        String email = UUID.randomUUID() + "@example.invalid";
        jdbc.update("INSERT INTO app_user (email, password_hash, nickname, status, created_at) "
                + "VALUES (?, 'integration-test-only', '测试用户', 'ACTIVE', CURRENT_TIMESTAMP)", email);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    }
}
