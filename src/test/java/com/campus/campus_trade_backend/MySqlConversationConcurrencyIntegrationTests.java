package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.messaging.dto.ConversationContextType;
import com.campus.campus_trade_backend.messaging.dto.StartConversationRequest;
import com.campus.campus_trade_backend.messaging.service.MessagingService;
import com.campus.campus_trade_backend.order.service.OrderService;
import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "campus.conversation.mysql.url", matches = "jdbc:mysql:.*")
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
class MySqlConversationConcurrencyIntegrationTests {
    @DynamicPropertySource
    static void isolatedMySql(DynamicPropertyRegistry registry) {
        String url = System.getProperty("campus.conversation.mysql.url");
        if (!url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):\\d+/campus_conversation_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("会话并发测试只能连接本机的 campus_conversation_test 独立数据库");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getProperty("campus.conversation.mysql.user", "campus_conversation"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CAMPUS_CONVERSATION_MYSQL_PASSWORD", ""));
    }

    @Autowired MessagingService messagingService;
    @Autowired ProductService productService;
    @Autowired OrderService orderService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createsOnceAndReusesTheSameConversation() {
        long seller = user("普通卖家");
        long buyer = user("普通买家");
        var request = productRequest(seller);

        long first = messagingService.start(request, buyer).id();
        assertEquals(first, messagingService.start(request, buyer).id());
        assertSingle(request, seller, buyer);
    }

    @Test
    @Timeout(45)
    void twoConcurrentRequestsReturnOneConversation() throws Exception {
        long seller = user("双线程卖家");
        long buyer = user("双线程买家");
        var request = productRequest(seller);

        List<Long> ids = startTogether(request, List.of(buyer, buyer));
        assertEquals(ids.get(0), ids.get(1));
        assertSingle(request, seller, buyer);
    }

    @Test
    @Timeout(60)
    void sixteenConcurrentRequestsReturnOneConversation() throws Exception {
        long seller = user("十六线程卖家");
        long buyer = user("十六线程买家");
        var request = productRequest(seller);

        List<Long> ids = startTogether(request, java.util.Collections.nCopies(16, buyer));
        assertEquals(1, ids.stream().distinct().count());
        assertSingle(request, seller, buyer);
    }

    @Test
    @Timeout(45)
    void buyerAndSellerStartingOrderConversationConcurrentlyGetOneId() throws Exception {
        long seller = user("订单卖家");
        long buyer = user("订单买家");
        long product = product(seller);
        long orderId = orderService.create(product, buyer, UUID.randomUUID().toString()).id();
        var request = new StartConversationRequest(ConversationContextType.ORDER, orderId);

        List<Long> ids = startTogether(request, List.of(seller, buyer));
        assertEquals(ids.get(0), ids.get(1));
        assertSingle(request, seller, buyer);
    }

    @Test
    @Timeout(45)
    void independentPairsCanStartConversationsConcurrently() throws Exception {
        long sellerA = user("卖家甲");
        long buyerA = user("买家甲");
        long sellerB = user("卖家乙");
        long buyerB = user("买家乙");
        var requestA = productRequest(sellerA);
        var requestB = productRequest(sellerB);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Long> first = executor.submit(() -> startAfterBarrier(ready, start, requestA, buyerA));
            Future<Long> second = executor.submit(() -> startAfterBarrier(ready, start, requestB, buyerB));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertNotEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
            assertSingle(requestA, sellerA, buyerA);
            assertSingle(requestB, sellerB, buyerB);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private List<Long> startTogether(StartConversationRequest request, List<Long> callers) throws Exception {
        var executor = Executors.newFixedThreadPool(callers.size());
        CountDownLatch ready = new CountDownLatch(callers.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Long>> futures = new ArrayList<>();
            for (long caller : callers) {
                futures.add(executor.submit(() -> startAfterBarrier(ready, start, request, caller)));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<Long> ids = new ArrayList<>();
            for (Future<Long> future : futures) ids.add(future.get(30, TimeUnit.SECONDS));
            return ids;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private long startAfterBarrier(CountDownLatch ready, CountDownLatch start,
                                   StartConversationRequest request, long caller) throws InterruptedException {
        ready.countDown();
        start.await();
        return messagingService.start(request, caller).id();
    }

    private StartConversationRequest productRequest(long seller) {
        return new StartConversationRequest(ConversationContextType.PRODUCT, product(seller));
    }

    private long product(long seller) {
        return productService.create(new CreateProductRequest(1L, "会话并发测试商品", "仅供隔离数据库测试",
                new BigDecimal("29.00"), ConditionLevel.GOOD), seller);
    }

    private long user(String nickname) {
        String email = UUID.randomUUID() + "@example.invalid";
        jdbc.update("""
                INSERT INTO app_user (email, password_hash, nickname, status, created_at)
                VALUES (?, 'integration-test-only', ?, 'ACTIVE', CURRENT_TIMESTAMP)
                """, email, nickname);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    }

    private void assertSingle(StartConversationRequest request, long first, long second) {
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM conversation
                WHERE context_type = ? AND context_id = ?
                  AND participant_low_id = ? AND participant_high_id = ?
                """, Integer.class, request.contextType().name(), request.contextId(),
                Math.min(first, second), Math.max(first, second)));
    }
}
