package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.order.service.OrderService;
import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.dto.UpdateProductRequest;
import com.campus.campus_trade_backend.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "campus.optimistic.mysql.url", matches = "jdbc:mysql:.*")
@ActiveProfiles("mysql")
@SpringBootTest(properties = {
        "CAMPUS_DB_PASSWORD=integration-test-only",
        "CAMPUS_JWT_SECRET=QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=",
        "spring.flyway.baseline-on-migrate=false",
        "spring.data.redis.url=redis://127.0.0.1:6379",
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=1025",
        "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "campus.mail.from=noreply@campustrade.local"
})
class MySqlProductOptimisticLockIntegrationTests {
    @DynamicPropertySource
    static void isolatedMySql(DynamicPropertyRegistry registry) {
        String url = System.getProperty("campus.optimistic.mysql.url");
        if (!url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):\\d+/campus_optimistic_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("乐观锁测试只能连接本机的 campus_optimistic_test 独立数据库");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getProperty("campus.optimistic.mysql.user", "campus_optimistic"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CAMPUS_OPTIMISTIC_MYSQL_PASSWORD", ""));
    }

    @Autowired ProductService productService;
    @Autowired OrderService orderService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void normalEditBumpsVersionAndStaleEditGetsConflict() {
        long seller = user();
        long productId = product(seller);
        assertEquals(0, detail(productId, seller).version());

        var updated = productService.update(productId, seller, edit(0, "第一版标题"));
        assertEquals("第一版标题", updated.title());
        assertEquals(1, updated.version());

        ResponseStatusException stale = assertThrows(ResponseStatusException.class,
                () -> productService.update(productId, seller, edit(0, "过期标题")));
        assertEquals(HttpStatus.CONFLICT, stale.getStatusCode());
        assertEquals("第一版标题", title(productId));
        assertEquals(1, version(productId));
    }

    @Test
    void anotherUserCannotEditEvenWithCurrentVersion() {
        long seller = user();
        long intruder = user();
        long productId = product(seller);

        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
                () -> productService.update(productId, intruder, edit(0, "越权标题")));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
        assertEquals("原始标题", title(productId));
        assertEquals(0, version(productId));
    }

    @Test
    void reservationAndReleaseInvalidateOldEditVersion() {
        long seller = user();
        long buyer = user();
        long productId = product(seller);
        var staleEdit = edit(0, "过期编辑");

        var order = orderService.create(productId, buyer, UUID.randomUUID().toString());
        assertEquals("RESERVED", status(productId));
        assertEquals(1, version(productId));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> productService.update(productId, seller, staleEdit)).getStatusCode());

        orderService.cancel(order.id(), buyer);
        assertEquals("ON_SALE", status(productId));
        assertEquals(2, version(productId));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> productService.update(productId, seller, staleEdit)).getStatusCode());
        assertEquals("原始标题", title(productId));

        productService.takeDown(productId, seller);
        assertEquals("OFF_SHELF", status(productId));
        assertEquals(3, version(productId));
    }

    @Test
    @Timeout(40)
    void twoConcurrentEditsHaveExactlyOneWinner() throws Exception {
        long seller = user();
        long productId = product(seller);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = List.of("并发标题 A", "并发标题 B").stream()
                    .map(title -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        try {
                            productService.update(productId, seller, edit(0, title));
                            return true;
                        } catch (ResponseStatusException conflict) {
                            assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
                            return false;
                        }
                    })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) if (result.get(25, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(1, version(productId));
            assertTrue(List.of("并发标题 A", "并发标题 B").contains(title(productId)));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private com.campus.campus_trade_backend.product.dto.ProductDetailResponse detail(long id, long seller) {
        return productService.findVisibleById(id, seller).orElseThrow();
    }

    private UpdateProductRequest edit(int version, String title) {
        return new UpdateProductRequest(version, 1L, title, "商品编辑乐观锁测试",
                new BigDecimal("29.00"), ConditionLevel.GOOD);
    }

    private long product(long seller) {
        return productService.create(new CreateProductRequest(1L, "原始标题", "商品编辑乐观锁测试",
                new BigDecimal("29.00"), ConditionLevel.GOOD), seller);
    }

    private long user() {
        String email = UUID.randomUUID() + "@example.invalid";
        jdbc.update("""
                INSERT INTO app_user (email, password_hash, nickname, status, created_at)
                VALUES (?, 'not-a-real-password', '乐观锁测试用户', 'ACTIVE', CURRENT_TIMESTAMP)
                """, email);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    }

    private String title(long id) {
        return jdbc.queryForObject("SELECT title FROM product WHERE id = ?", String.class, id);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM product WHERE id = ?", String.class, id);
    }

    private int version(long id) {
        return jdbc.queryForObject("SELECT version FROM product WHERE id = ?", Integer.class, id);
    }
}
