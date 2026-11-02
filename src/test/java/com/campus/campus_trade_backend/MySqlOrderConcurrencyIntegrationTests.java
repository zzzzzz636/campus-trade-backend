package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.messaging.dto.ConversationContextType;
import com.campus.campus_trade_backend.messaging.dto.StartConversationRequest;
import com.campus.campus_trade_backend.messaging.service.MessagingService;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "campus.mysql.tests", matches = "true")
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
        "campus.mail.from=noreply@campustrade.local",
        "campus.messaging.max-per-minute=100"
})
class MySqlOrderConcurrencyIntegrationTests {
    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("campus_trade")
            .withUsername("campus_trade")
            .withPassword("integration-test-only");

    @Autowired ProductService productService;
    @Autowired OrderService orderService;
    @Autowired MessagingService messagingService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayCreatesCurrentSchemaAndRequiredIndexes() {
        assertEquals(5L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Long.class));
        assertEquals(5L, jdbc.queryForObject("SELECT COUNT(*) FROM category", Long.class));
        assertTrue(indexExists("product", "idx_product_status_created_id"));
        assertTrue(indexExists("trade_order", "uq_order_buyer_request"));
        assertTrue(indexExists("product_image", "idx_product_image_product"));
        assertTrue(indexExists("trade_order", "idx_order_status_expires"));
        assertTrue(indexExists("private_message", "idx_message_conversation_id"));
        assertTrue(indexExists("product_favorite", "idx_favorite_user_created"));
        assertTrue(indexExists("order_cancel_request", "idx_cancel_request_order_status"));
    }

    @Test
    @Timeout(45)
    void innodbAllowsOnlyOneBuyerAndRollsBackDuplicateRequestReservation() throws Exception {
        long sellerId = user("mysql-seller");
        long productId = product(sellerId, "MySQL 并发商品");
        List<Long> buyers = new ArrayList<>();
        for (int i = 0; i < 8; i++) buyers.add(user("mysql-buyer-" + i));

        var executor = Executors.newFixedThreadPool(buyers.size());
        CountDownLatch ready = new CountDownLatch(buyers.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (long buyerId : buyers) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        orderService.create(productId, buyerId, UUID.randomUUID().toString());
                        return true;
                    } catch (ResponseStatusException exception) {
                        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            long successes = 0;
            for (Future<Boolean> future : futures) if (future.get(30, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM trade_order WHERE product_id = ? AND status = 'PENDING_SELLER_CONFIRMATION'",
                    Long.class, productId));
            assertEquals("RESERVED", jdbc.queryForObject(
                    "SELECT status FROM product WHERE id = ?", String.class, productId));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }

        long firstProduct = product(sellerId, "幂等请求商品一");
        long secondProduct = product(sellerId, "幂等请求商品二");
        long retryBuyer = user("mysql-idempotent-buyer");
        String requestKey = UUID.randomUUID().toString();
        orderService.create(firstProduct, retryBuyer, requestKey);

        ResponseStatusException duplicate = assertThrows(ResponseStatusException.class,
                () -> orderService.create(secondProduct, retryBuyer, requestKey));
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
        assertEquals("ON_SALE", jdbc.queryForObject(
                "SELECT status FROM product WHERE id = ?", String.class, secondProduct));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade_order WHERE product_id = ?", Long.class, secondProduct));
    }

    @Test
    void mysqlExecutesProductFiltersSortAndEdit() {
        long sellerId = user("mysql-v3-seller");
        String marker = UUID.randomUUID().toString().substring(0, 8);
        productService.create(new CreateProductRequest(1L, marker + " 教材", "MySQL V3 查询",
                new BigDecimal("30.00"), ConditionLevel.GOOD), sellerId);
        productService.create(new CreateProductRequest(2L, marker + " 耳机", "MySQL V3 查询",
                new BigDecimal("10.00"), ConditionLevel.LIKE_NEW), sellerId);
        long editableId = productService.create(new CreateProductRequest(2L, marker + " 台灯", "MySQL V3 查询",
                new BigDecimal("20.00"), ConditionLevel.GOOD), sellerId);

        var filtered = productService.list(1, 10, marker, 2L,
                new BigDecimal("15.00"), new BigDecimal("25.00"), "PRICE_DESC");
        assertEquals(1, filtered.total());
        assertEquals(editableId, filtered.items().get(0).id());
        assertEquals("数码与电器", filtered.items().get(0).categoryName());
        assertTrue(filtered.items().get(0).createdAt() != null);

        var updated = productService.update(editableId, sellerId,
                new UpdateProductRequest(1L, marker + " 已更新", "MySQL 条件更新",
                        new BigDecimal("18.50"), ConditionLevel.FAIR));
        assertEquals(marker + " 已更新", updated.title());
        assertEquals("教材", updated.categoryName());
        assertEquals(new BigDecimal("18.50"), updated.price());

        long buyerId = user("mysql-v3-buyer");
        var order = orderService.create(editableId, buyerId, UUID.randomUUID().toString());
        assertEquals(new BigDecimal("18.50"), order.price());
        assertEquals(marker + " 已更新", order.productTitle());
    }

    @Test
    void mysqlPersistsHistoryAndSupportsIncrementalMessageCursor() {
        long sellerId = user("mysql-message-seller");
        long buyerId = user("mysql-message-buyer");
        long productId = product(sellerId, "MySQL 私信游标商品");
        var conversation = messagingService.start(
                new StartConversationRequest(ConversationContextType.PRODUCT, productId), buyerId);

        long latestBuyerMessageId = 0;
        for (int index = 1; index <= 35; index++) {
            latestBuyerMessageId = messagingService.send(
                    conversation.id(), buyerId, "MySQL 历史消息 " + index).id();
        }
        var firstPage = messagingService.messages(conversation.id(), buyerId, null, 30);
        assertEquals(30, firstPage.items().size());
        assertTrue(firstPage.hasMore());
        assertTrue(firstPage.nextBeforeId() != null);
        assertEquals("MySQL 历史消息 35", firstPage.items().get(29).content());

        messagingService.send(conversation.id(), sellerId, "MySQL 增量回复");
        var incremental = messagingService.messages(
                conversation.id(), buyerId, null, latestBuyerMessageId, 20);
        assertEquals(1, incremental.items().size());
        assertEquals("MySQL 增量回复", incremental.items().get(0).content());
        assertEquals(36L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM private_message WHERE conversation_id = ?",
                Long.class, conversation.id()));
    }

    @Test
    @Timeout(30)
    void sellerAcceptAndBuyerCancelRaceLeavesOneConsistentOutcome() throws Exception {
        long sellerId = user("mysql-race-seller");
        long buyerId = user("mysql-race-buyer");
        long productId = product(sellerId, "接受取消竞态商品");
        var order = orderService.create(productId, buyerId, UUID.randomUUID().toString());
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> accepted = executor.submit(() -> raceAction(ready, start,
                    () -> orderService.accept(order.id(), sellerId, null)));
            Future<Boolean> cancelled = executor.submit(() -> raceAction(ready, start,
                    () -> orderService.cancel(order.id(), buyerId)));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, (accepted.get(15, TimeUnit.SECONDS) ? 1 : 0)
                    + (cancelled.get(15, TimeUnit.SECONDS) ? 1 : 0));

            String orderStatus = jdbc.queryForObject("SELECT status FROM trade_order WHERE id = ?", String.class, order.id());
            String productStatus = jdbc.queryForObject("SELECT status FROM product WHERE id = ?", String.class, productId);
            if ("WAITING_HANDOFF".equals(orderStatus)) assertEquals("RESERVED", productStatus);
            else {
                assertEquals("CANCELLED", orderStatus);
                assertEquals("ON_SALE", productStatus);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @Timeout(30)
    void negotiatedCancellationResolutionIsSerializedInInnoDb() throws Exception {
        long sellerId = user("negotiation-seller");
        long buyerId = user("negotiation-buyer");
        long productId = product(sellerId, "协商取消并发商品");
        var order = orderService.create(productId, buyerId, UUID.randomUUID().toString());
        orderService.accept(order.id(), sellerId, null);
        orderService.proposeCancellation(order.id(), buyerId, "不再需要");
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> accepted = executor.submit(() -> raceAction(ready, start,
                    () -> orderService.resolveCancellation(order.id(), sellerId, true)));
            Future<Boolean> rejected = executor.submit(() -> raceAction(ready, start,
                    () -> orderService.resolveCancellation(order.id(), sellerId, false)));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, (accepted.get(15, TimeUnit.SECONDS) ? 1 : 0)
                    + (rejected.get(15, TimeUnit.SECONDS) ? 1 : 0));
            String status = jdbc.queryForObject("SELECT status FROM trade_order WHERE id = ?", String.class, order.id());
            String productStatus = jdbc.queryForObject("SELECT status FROM product WHERE id = ?", String.class, productId);
            String requestStatus = jdbc.queryForObject("SELECT status FROM order_cancel_request WHERE order_id = ?", String.class, order.id());
            if ("CANCELLED".equals(status)) {
                assertEquals("ON_SALE", productStatus);
                assertEquals("ACCEPTED", requestStatus);
            } else {
                assertEquals("WAITING_HANDOFF", status);
                assertEquals("RESERVED", productStatus);
                assertEquals("REJECTED", requestStatus);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private boolean raceAction(CountDownLatch ready, CountDownLatch start, Runnable action) throws Exception {
        ready.countDown();
        start.await();
        try {
            action.run();
            return true;
        } catch (ResponseStatusException exception) {
            assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
            return false;
        }
    }

    private long user(String nickname) {
        String email = UUID.randomUUID() + "@example.invalid";
        jdbc.update("""
                INSERT INTO app_user (email, password_hash, nickname, status, created_at)
                VALUES (?, 'integration-test-only', ?, 'ACTIVE', CURRENT_TIMESTAMP)
                """, email, nickname);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    }

    private long product(long sellerId, String title) {
        return productService.create(new CreateProductRequest(1L, title, "MySQL InnoDB 集成测试",
                new BigDecimal("39.00"), ConditionLevel.GOOD), sellerId);
    }

    private boolean indexExists(String table, String index) {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                """, Long.class, table, index);
        return count != null && count > 0;
    }
}
