package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.auth.dto.LoginRequest;
import com.campus.campus_trade_backend.auth.dto.RegisterRequest;
import com.campus.campus_trade_backend.auth.service.AuthService;
import com.campus.campus_trade_backend.auth.service.RegistrationService;
import com.campus.campus_trade_backend.auth.verification.LocalConsoleMailSender;
import com.campus.campus_trade_backend.auth.verification.VerificationService;
import com.campus.campus_trade_backend.order.dto.CreateOrderRequest;
import com.campus.campus_trade_backend.order.service.OrderService;
import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.service.ProductService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campustrade_aiagent;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "campus.ai.enabled=true", "campus.ai.api-key=fake-test-key", "campus.ai.model=test-model"
})
@AutoConfigureMockMvc
@Transactional
class AiAgentIntegrationTests {
    private static final HttpServer server = startServer();
    private static final AtomicReference<String> toolName = new AtomicReference<>("searchProducts");
    private static final AtomicReference<String> arguments = new AtomicReference<>("{}");
    private static final List<String> requests = new ArrayList<>();

    @DynamicPropertySource
    static void aiUrl(DynamicPropertyRegistry registry) {
        registry.add("campus.ai.base-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll static void stop() { server.stop(0); }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ProductService products;
    @Autowired OrderService orders;
    @Autowired VerificationService verification;
    @Autowired RegistrationService registration;
    @Autowired LocalConsoleMailSender mail;
    @Autowired AuthService auth;

    @Test
    void modelSearchReallyQueriesVisibleDatabaseProducts() throws Exception {
        User seller = user();
        User buyer = user();
        long shown = products.create(product("机械键盘", "校内面交", "199"), seller.id());
        long hidden = products.create(product("另一把机械键盘", "校内面交", "250"), seller.id());
        products.takeDown(hidden, seller.id());
        toolName.set("searchProducts");
        arguments.set("{\"query\":\"机械键盘\",\"categoryId\":null,\"minPrice\":null,\"maxPrice\":300}");
        requests.clear();

        JsonNode response = chat(buyer.token(), "找 300 元以内的机械键盘", null);
        assertEquals(shown, response.path("products").get(0).path("id").asLong());
        assertEquals(1, response.path("products").size());
        assertEquals(2, requests.size());
        assertTrue(requests.get(1).contains("function_call_output"));
        assertTrue(requests.get(1).contains("机械键盘"));
        assertFalse(requests.get(1).contains("另一把机械键盘"));
    }

    @Test
    void orderToolUsesJwtUserAndRejectsInjectedUserId() throws Exception {
        User seller = user();
        User buyer = user();
        User stranger = user();
        long product = products.create(product("教材", "校内面交", "20"), seller.id());
        long order = orders.create(new CreateOrderRequest(product, UUID.randomUUID().toString(), null,
                null, "图书馆", null, null, null), buyer.id()).id();
        toolName.set("getMyOrders");
        arguments.set("{}");
        requests.clear();
        JsonNode own = chat(buyer.token(), "查看我的订单", null);
        assertEquals(order, own.path("orders").get(0).path("id").asLong());
        JsonNode other = chat(stranger.token(), "查看我的订单", null);
        assertEquals(0, other.path("orders").size());

        arguments.set("{\"userId\":" + buyer.id() + "}");
        JsonNode malicious = chat(stranger.token(), "查看另一个用户订单", null);
        assertEquals(0, malicious.path("orders").size());
        assertFalse(requests.get(requests.size() - 1).contains("\"productTitle\":\"教材\""));
    }

    @Test
    void detailToolCannotReturnOffShelfOrNonexistentProducts() throws Exception {
        User seller = user();
        User viewer = user();
        long visible = products.create(product("真实台灯", "九成新，图书馆面交", "35"), seller.id());
        long hidden = products.create(product("已下架台灯", "不展示", "40"), seller.id());
        products.takeDown(hidden, seller.id());
        toolName.set("getProductDetail");
        arguments.set("{\"productId\":" + visible + "}");
        JsonNode found = chat(viewer.token(), "看台灯详情", null);
        assertEquals(visible, found.path("products").get(0).path("id").asLong());
        arguments.set("{\"productId\":" + hidden + "}");
        JsonNode notVisible = chat(viewer.token(), "看另一盏台灯", null);
        assertEquals(0, notVisible.path("products").size());
        arguments.set("{\"productId\":9999999}");
        JsonNode missing = chat(viewer.token(), "看不存在的商品", null);
        assertEquals(0, missing.path("products").size());
    }

    @Test
    void conversationHistoryIsScopedToOwnerAndUnknownSessionIsRejected() throws Exception {
        User a = user();
        User b = user();
        toolName.set("searchProducts");
        arguments.set("{\"query\":\"键盘\",\"categoryId\":null,\"minPrice\":null,\"maxPrice\":300}");
        requests.clear();
        JsonNode first = chat(a.token(), "找 300 元内的键盘", null);
        String session = first.path("sessionId").asString();
        chat(a.token(), "有没有更便宜的", session);
        assertTrue(requests.get(2).contains("找 300 元内的键盘"));
        mvc.perform(post("/api/ai/agent/chat").header("Authorization", "Bearer " + b.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new Chat("替别人继续", session))))
                .andExpect(status().isConflict());
    }

    @Test
    void draftToolReturnsEditableSuggestionWithoutPublishing() throws Exception {
        User user = user();
        toolName.set("generateProductDraft");
        arguments.set("{\"rawTitle\":\"旧教材\",\"rawDescription\":\"书页完整，校内面交\",\"conditionLevel\":\"GOOD\"}");
        JsonNode reply = chat(user.token(), "帮我整理这本教材的发布草稿", null);
        assertEquals("整理后的教材", reply.path("draft").path("title").asString());
        assertEquals(1L, reply.path("draft").path("categoryId").asLong());
        assertEquals(0, products.listMine(user.id()).size());
    }

    @Test
    void invalidToolNameIsRejectedBeforeAnyBusinessAccess() throws Exception {
        User user = user();
        toolName.set("executeSql");
        arguments.set("{}");
        mvc.perform(post("/api/ai/agent/chat").header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"运行 SQL\"}"))
                .andExpect(status().isBadGateway());
    }

    @Test
    void unauthenticatedOrOverlongInputCannotReachModel() throws Exception {
        mvc.perform(post("/api/ai/agent/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"查商品\"}"))
                .andExpect(status().isUnauthorized());
        User user = user();
        mvc.perform(post("/api/ai/agent/chat").header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new Chat("a".repeat(501), null))))
                .andExpect(status().isBadRequest());
    }

    private JsonNode chat(String token, String message, String sessionId) throws Exception {
        String raw = mvc.perform(post("/api/ai/agent/chat").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new Chat(message, sessionId))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(raw);
    }

    private User user() {
        String email = UUID.randomUUID() + "@example.invalid";
        verification.send(email);
        long id = registration.register(new RegisterRequest(email, "password123", "同学",
                mail.latestCodeForTest(email))).id();
        return new User(id, auth.login(new LoginRequest(email, "password123")).accessToken());
    }

    private CreateProductRequest product(String title, String description, String price) {
        return new CreateProductRequest(1L, title, description, new BigDecimal(price), ConditionLevel.GOOD);
    }

    private static HttpServer startServer() {
        try {
            HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            created.createContext("/responses", AiAgentIntegrationTests::respond);
            created.start();
            return created;
        } catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(request);
        String body = request.contains("\"campus_trade_product_draft\"")
                ? "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"title\\\":\\\"整理后的教材\\\",\\\"description\\\":\\\"书页完整，校内面交\\\",\\\"categoryId\\\":1}\"}]}]}"
                : request.contains("function_call_output")
                ? "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"已根据平台数据查询，请查看结果卡片。\"}]}]}"
                : "{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"name\":\""
                    + toolName.get() + "\",\"call_id\":\"call_1\",\"arguments\":"
                    + new ObjectMapper().writeValueAsString(arguments.get()) + "}]}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record User(long id, String token) { }
    private record Chat(String message, String sessionId) { }
}
