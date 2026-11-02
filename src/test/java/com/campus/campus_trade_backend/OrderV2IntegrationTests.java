package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.auth.dto.RegisterRequest;
import com.campus.campus_trade_backend.auth.dto.UserResponse;
import com.campus.campus_trade_backend.auth.service.AuthService;
import com.campus.campus_trade_backend.auth.service.RegistrationService;
import com.campus.campus_trade_backend.auth.verification.LocalConsoleMailSender;
import com.campus.campus_trade_backend.auth.verification.VerificationService;
import com.campus.campus_trade_backend.auth.security.JwtService;
import com.campus.campus_trade_backend.order.service.OrderService;
import com.campus.campus_trade_backend.order.mapper.OrderMapper;
import com.campus.campus_trade_backend.order.service.NotificationService;
import com.campus.campus_trade_backend.order.dto.CreateOrderRequest;
import com.campus.campus_trade_backend.order.dto.FulfillmentType;
import com.campus.campus_trade_backend.order.dto.ShipOrderRequest;
import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.service.ProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:campustrade_orderv2;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
class OrderV2IntegrationTests {
    @Autowired AuthService authService;
    @Autowired RegistrationService registrationService;
    @Autowired VerificationService verificationService;
    @Autowired LocalConsoleMailSender localMail;
    @Autowired ProductService productService;
    @Autowired OrderService orderService;
    @Autowired OrderMapper orderMapper;
    @Autowired NotificationService notificationService;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @Test
    void anonymousUsersCannotReadOrChangePrivateData() throws Exception {
        mvc.perform(get("/api/orders/sales")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/orders/1/cancel")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/products/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void disablingUserInvalidatesAnAlreadyIssuedJwt() throws Exception {
        UserResponse user = user("待禁用用户");
        String token = "Bearer " + jwtService.issue(user.id()).accessToken();
        mvc.perform(get("/api/products/me").header("Authorization", token))
                .andExpect(status().isOk());
        jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE id = ?", user.id());
        mvc.perform(get("/api/products/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void orderHttpRoutesUseJwtIdentity() throws Exception {
        UserResponse seller = user("卖家");
        UserResponse buyer = user("买家");
        long productId = product(seller.id());
        var order = orderService.create(productId, buyer.id(), key());
        String sellerToken = "Bearer " + jwtService.issue(seller.id()).accessToken();
        String buyerToken = "Bearer " + jwtService.issue(buyer.id()).accessToken();

        mvc.perform(get("/api/orders/sales").header("Authorization", sellerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(order.id()));
        mvc.perform(post("/api/orders/{id}/cancel", order.id()).header("Authorization", sellerToken))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/{id}/cancel", order.id()).header("Authorization", buyerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/orders/{id}/cancel", order.id()).header("Authorization", buyerToken))
                .andExpect(status().isConflict());
        var nextOrder = orderService.create(productId, buyer.id(), key());
        mvc.perform(post("/api/orders/{id}/accept", nextOrder.id()).header("Authorization", sellerToken)
                        .contentType("application/json").content("{\"note\":\"校门口面交\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING_HANDOFF"));
        mvc.perform(post("/api/orders/{id}/complete", nextOrder.id()).header("Authorization", buyerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @AfterEach
    void removeTestRows() {
        for (long productId : productIds) jdbc.update("DELETE FROM app_notification WHERE reference_id IN (SELECT id FROM trade_order WHERE product_id = ?)", productId);
        for (long productId : productIds) jdbc.update("DELETE FROM order_event WHERE order_id IN (SELECT id FROM trade_order WHERE product_id = ?)", productId);
        for (long productId : productIds) jdbc.update("DELETE FROM trade_order WHERE product_id = ?", productId);
        for (long productId : productIds) jdbc.update("DELETE FROM product WHERE id = ?", productId);
        for (long userId : userIds) jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    @Test
    void cancellationAndCompletionRespectStateAndOwnership() {
        UserResponse seller = user("卖家");
        UserResponse buyer = user("买家");
        UserResponse other = user("其他用户");
        long productId = product(seller.id());

        var order = orderService.create(productId, buyer.id(), key());
        assertEquals("PENDING_SELLER_CONFIRMATION", order.status());
        assertEquals(order.id(), orderService.listSales(seller.id()).get(0).id());
        assertEquals(order.id(), orderService.listPurchases(buyer.id()).get(0).id());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> orderService.findById(order.id(), other.id())).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> orderService.cancel(order.id(), seller.id())).getStatusCode());

        assertEquals("CANCELLED", orderService.cancel(order.id(), buyer.id()).status());
        assertTrue(productService.findById(productId).isPresent());
        assertEquals("ON_SALE", productService.listMine(seller.id()).get(0).status());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> orderService.cancel(order.id(), buyer.id())).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> orderService.complete(order.id(), buyer.id())).getStatusCode());

        var nextOrder = orderService.create(productId, other.id(), key());
        assertEquals("WAITING_HANDOFF", orderService.accept(nextOrder.id(), seller.id(), "同意面交").status());
        assertEquals("COMPLETED", orderService.complete(nextOrder.id(), other.id()).status());
        assertEquals("SOLD", productService.listMine(seller.id()).get(0).status());
        assertFalse(productService.findById(productId).isPresent());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> orderService.complete(nextOrder.id(), other.id())).getStatusCode());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> orderService.cancel(nextOrder.id(), other.id())).getStatusCode());
    }

    @Test
    void duplicateRequestKeyRollsBackReservation() {
        UserResponse seller = user("卖家");
        UserResponse buyer = user("买家");
        long first = product(seller.id());
        long second = product(seller.id());
        String requestKey = key();

        orderService.create(first, buyer.id(), requestKey);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> orderService.create(second, buyer.id(), requestKey)).getStatusCode());
        assertTrue(productService.findById(second).isPresent());
        assertEquals("ON_SALE", jdbc.queryForObject("SELECT status FROM product WHERE id = ?", String.class, second));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE product_id = ?", Long.class, second));
    }

    @Test
    void failedProductTransitionRollsBackOrderStatus() {
        UserResponse seller = user("卖家");
        UserResponse buyer = user("买家");
        long productId = product(seller.id());
        var order = orderService.create(productId, buyer.id(), key());

        // 故意制造数据库状态不一致，确认事务会撤销已写入的订单状态。
        jdbc.update("UPDATE product SET status = 'OFF_SHELF' WHERE id = ?", productId);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> orderService.cancel(order.id(), buyer.id())).getStatusCode());
        assertEquals("PENDING_SELLER_CONFIRMATION", orderService.findById(order.id(), buyer.id()).status());
    }

    @Test
    @Timeout(30)
    void concurrentBuyersCanCreateOnlyOneOrder() throws Exception {
        UserResponse seller = user("卖家");
        long productId = product(seller.id());
        List<UserResponse> buyers = new ArrayList<>();
        for (int i = 0; i < 6; i++) buyers.add(user("买家" + i));

        var executor = Executors.newFixedThreadPool(buyers.size());
        CountDownLatch ready = new CountDownLatch(buyers.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (UserResponse buyer : buyers) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        orderService.create(productId, buyer.id(), key());
                        return true;
                    } catch (ResponseStatusException e) {
                        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            long successful = 0;
            for (Future<Boolean> future : futures) if (future.get(15, TimeUnit.SECONDS)) successful++;
            assertEquals(1, successful);
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM trade_order WHERE product_id = ? AND status = 'PENDING_SELLER_CONFIRMATION'", Long.class, productId));
            assertEquals("RESERVED", jdbc.queryForObject("SELECT status FROM product WHERE id = ?", String.class, productId));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shippingOrderRequiresSellerAcceptanceAndShipmentBeforeReceipt() {
        UserResponse seller = user("快递卖家");
        UserResponse buyer = user("快递买家");
        UserResponse other = user("无关用户");
        long productId = product(seller.id());
        var request = new CreateOrderRequest(productId, key(), FulfillmentType.SHIPPING,
                "请包好一点", null, "张同学", "13800000000", "测试大学 1 号宿舍");

        var order = orderService.create(request, buyer.id());
        assertEquals("PENDING_SELLER_CONFIRMATION", order.status());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> orderService.complete(order.id(), buyer.id())).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> orderService.accept(order.id(), other.id(), null)).getStatusCode());
        // 未接受前不向卖家暴露收货地址；接受后才用于履约。
        assertEquals(null, orderService.findDetail(order.id(), seller.id()).shippingAddress());
        assertEquals("WAITING_SHIPMENT", orderService.accept(order.id(), seller.id(), "今晚发出").status());
        assertEquals("测试大学 1 号宿舍", orderService.findDetail(order.id(), seller.id()).shippingAddress());
        assertEquals("SHIPPED", orderService.ship(order.id(), seller.id(),
                new ShipOrderRequest("校园快递", "CT20260001")).status());
        assertEquals("COMPLETED", orderService.complete(order.id(), buyer.id()).status());
        assertEquals("SOLD", productService.listMine(seller.id()).get(0).status());
        assertEquals(4, orderService.findDetail(order.id(), buyer.id()).events().size());
    }

    @Test
    void acceptanceChecksDeadlineEvenBeforeExpiryJobRuns() {
        UserResponse seller = user("超时卖家");
        UserResponse buyer = user("超时买家");
        long productId = product(seller.id());
        var expired = orderService.create(productId, buyer.id(), key());
        jdbc.update("UPDATE trade_order SET expires_at = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(1), expired.id());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> orderService.accept(expired.id(), seller.id(), null)).getStatusCode());
        assertEquals("PENDING_SELLER_CONFIRMATION", orderService.findById(expired.id(), buyer.id()).status());
        assertEquals(1, orderService.expirePendingOrders(10));
        assertEquals("EXPIRED", orderService.findById(expired.id(), buyer.id()).status());
        assertEquals("ON_SALE", jdbc.queryForObject("SELECT status FROM product WHERE id = ?", String.class, productId));

        var before = orderService.create(productId, buyer.id(), key());
        jdbc.update("UPDATE trade_order SET expires_at = ? WHERE id = ?",
                LocalDateTime.now().plusHours(1), before.id());
        assertEquals("WAITING_HANDOFF", orderService.accept(before.id(), seller.id(), null).status());
    }

    @Test
    void exactDeadlineIsExclusiveAndPagesFilterByStatus() {
        UserResponse seller = user("分页卖家");
        UserResponse buyer = user("分页买家");
        long productId = product(seller.id());
        var first = orderService.create(productId, buyer.id(), key());
        LocalDateTime boundary = LocalDateTime.of(2030, 1, 1, 12, 0);
        jdbc.update("UPDATE trade_order SET expires_at = ? WHERE id = ?", boundary, first.id());
        assertEquals(0, orderMapper.acceptPending(first.id(), seller.id(), "WAITING_HANDOFF", null, boundary));
        assertEquals(1, orderMapper.acceptPending(first.id(), seller.id(), "WAITING_HANDOFF", null,
                boundary.minusSeconds(1)));
        assertEquals("WAITING_HANDOFF", orderService.findById(first.id(), buyer.id()).status());

        var purchases = orderService.listPurchases(buyer.id(), 1, 1, "WAITING_HANDOFF");
        assertEquals(1, purchases.total());
        assertEquals(first.id(), purchases.items().get(0).id());
        assertEquals(1, orderService.listSales(seller.id(), 1, 1, "WAITING_HANDOFF").total());
        assertEquals(0, orderService.listPurchases(buyer.id(), 2, 1, "WAITING_HANDOFF").items().size());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> orderService.listPurchases(buyer.id(), 1, 10, "WRONG")).getStatusCode());
    }

    @Test
    void notificationIsPrivatePagedAndReadIsIdempotent() {
        UserResponse seller = user("通知卖家");
        UserResponse buyer = user("通知买家");
        UserResponse stranger = user("通知路人");
        var order = orderService.create(product(seller.id()), buyer.id(), key());
        var sellerPage = notificationService.list(seller.id(), 1, 20);
        assertEquals(1, sellerPage.total());
        assertEquals("ORDER_APPLICATION", sellerPage.items().get(0).type());
        assertEquals(order.id(), sellerPage.items().get(0).referenceId());
        long notificationId = sellerPage.items().get(0).id();
        assertEquals(1, notificationService.unreadCount(seller.id()));
        assertEquals(0, notificationService.list(stranger.id(), 1, 20).total());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> notificationService.markRead(stranger.id(), notificationId)).getStatusCode());
        notificationService.markRead(seller.id(), notificationId);
        notificationService.markRead(seller.id(), notificationId);
        assertEquals(0, notificationService.unreadCount(seller.id()));
        assertEquals(0, notificationService.list(seller.id(), 2, 1).items().size());
    }

    private UserResponse user(String nickname) {
        String email = UUID.randomUUID() + "@example.invalid";
        verificationService.send(email);
        UserResponse user = registrationService.register(new RegisterRequest(email, "password123", nickname,
                localMail.latestCodeForTest(email)));
        userIds.add(user.id());
        return user;
    }

    private long product(long sellerId) {
        long id = productService.create(new CreateProductRequest(1L, "V2 测试商品", "校内面交",
                new BigDecimal("29.00"), ConditionLevel.GOOD), sellerId);
        productIds.add(id);
        return id;
    }

    private static String key() { return UUID.randomUUID().toString(); }
}
