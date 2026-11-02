package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.messaging.dto.ConversationContextType;
import com.campus.campus_trade_backend.messaging.dto.StartConversationRequest;
import com.campus.campus_trade_backend.messaging.service.MessagingService;
import com.campus.campus_trade_backend.order.service.OrderService;
import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campustrade_messaging;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "campus.messaging.max-per-minute=10"
})
class MessagingIntegrationTests {
    @Autowired MessagingService messagingService;
    @Autowired ProductService productService;
    @Autowired OrderService orderService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void participantsCanPersistReadAndPageMessagesButThirdUserCannotReadThem() {
        long seller = user("私信卖家");
        long buyer = user("私信买家");
        long third = user("无关用户");
        long product = productService.create(new CreateProductRequest(1L, "私信测试教材", "保持良好",
                new BigDecimal("25.00"), ConditionLevel.GOOD), seller);

        var context = new StartConversationRequest(ConversationContextType.PRODUCT, product);
        var conversation = messagingService.start(context, buyer);
        assertEquals(conversation.id(), messagingService.start(context, buyer).id());
        var firstMessage = messagingService.send(conversation.id(), buyer, "请问可以明天下午面交吗？");
        messagingService.send(conversation.id(), buyer, "我在东区。 ");

        var sellerConversations = messagingService.list(seller, 1, 20);
        assertEquals(1, sellerConversations.size());
        assertEquals(2, sellerConversations.get(0).unreadCount());
        assertEquals("私信测试教材", sellerConversations.get(0).contextTitle());
        assertEquals(2, messagingService.markRead(conversation.id(), seller));
        assertEquals(0, messagingService.list(seller, 1, 20).get(0).unreadCount());

        messagingService.send(conversation.id(), seller, "可以，食堂门口见。");
        var page = messagingService.messages(conversation.id(), buyer, null, 2);
        assertEquals(2, page.items().size());
        assertTrue(page.hasMore());
        assertTrue(page.nextBeforeId() != null);
        assertEquals("可以，食堂门口见。", page.items().get(1).content());

        var incremental = messagingService.messages(conversation.id(), buyer, null, firstMessage.id(), 20);
        assertEquals(2, incremental.items().size());
        assertEquals("我在东区。", incremental.items().get(0).content());
        assertEquals("可以，食堂门口见。", incremental.items().get(1).content());

        ResponseStatusException conflictingCursor = assertThrows(ResponseStatusException.class,
                () -> messagingService.messages(conversation.id(), buyer, firstMessage.id(), firstMessage.id(), 20));
        assertEquals(HttpStatus.BAD_REQUEST, conflictingCursor.getStatusCode());

        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
                () -> messagingService.messages(conversation.id(), third, null, 20));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
    }

    @Test
    void onlyOrderParticipantsCanCreateOrderConversation() {
        long seller = user("订单卖家");
        long buyer = user("订单买家");
        long third = user("第三人");
        long product = productService.create(new CreateProductRequest(1L, "订单会话商品", "会话上下文",
                new BigDecimal("49.00"), ConditionLevel.LIKE_NEW), seller);
        var order = orderService.create(product, buyer, UUID.randomUUID().toString());
        var request = new StartConversationRequest(ConversationContextType.ORDER, order.id());

        long buyerConversation = messagingService.start(request, buyer).id();
        assertEquals(buyerConversation, messagingService.start(request, seller).id());
        ResponseStatusException forbidden = assertThrows(ResponseStatusException.class,
                () -> messagingService.start(request, third));
        assertEquals(HttpStatus.FORBIDDEN, forbidden.getStatusCode());
    }

    private long user(String nickname) {
        String email = UUID.randomUUID() + "@example.invalid";
        jdbc.update("""
                INSERT INTO app_user (email, password_hash, nickname, status, created_at)
                VALUES (?, 'test-only', ?, 'ACTIVE', CURRENT_TIMESTAMP)
                """, email, nickname);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    }
}
