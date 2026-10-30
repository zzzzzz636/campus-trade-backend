package com.campus.campus_trade_backend.ai.client;

import com.campus.campus_trade_backend.ai.config.AiProperties;
import com.campus.campus_trade_backend.ai.dto.ProductDraftRequest;
import com.campus.campus_trade_backend.category.entity.CategoryEntity;
import com.campus.campus_trade_backend.category.mapper.CategoryMapper;
import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiProductDraftClientTests {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private AiProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        properties = new AiProperties();
        properties.setEnabled(true);
        properties.setApiKey("test-key-not-a-real-secret");
        properties.setModel("test-model");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setRequestTimeout(Duration.ofSeconds(2));
        properties.setRetryDelay(Duration.ofMillis(1));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsStrictSchemaAndAcceptsValidatedSuggestion() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, completed("高等数学教材，书页整洁", "正常使用，有少量笔记，校内面交。", 1));
        });

        var suggestion = client().generate(new ProductDraftRequest(
                "高数书", "用过一个学期，有一点笔记，东门面交", ConditionLevel.GOOD));

        assertEquals("高等数学教材，书页整洁", suggestion.title());
        assertEquals(1L, suggestion.categoryId());
        assertEquals("教材与书籍", suggestion.categoryName());
        JsonNode sent = objectMapper.readTree(requestBody.get());
        assertEquals("json_schema", sent.path("text").path("format").path("type").asString());
        assertTrue(sent.path("text").path("format").path("strict").asBoolean());
        assertFalse(requestBody.get().contains("test-key-not-a-real-secret"));
    }

    @Test
    void retriesOneTransientFailureThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/responses", exchange -> {
            if (calls.incrementAndGet() == 1) respond(exchange, 500, "");
            else respond(exchange, 200, completed("台灯", "功能正常，外观有轻微使用痕迹。", 2));
        });

        var suggestion = client().generate(new ProductDraftRequest(
                "台灯", "台灯正常用过，有一点划痕", ConditionLevel.GOOD));

        assertEquals(2, calls.get());
        assertEquals(2L, suggestion.categoryId());
    }

    @Test
    void rejectsCategoryThatDoesNotExistLocally() {
        server.createContext("/responses", exchange ->
                respond(exchange, 200, completed("未知商品", "描述", 999)));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> client().generate(new ProductDraftRequest(
                        "未知", "一段足够明确的原始商品描述", ConditionLevel.FAIR)));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        assertEquals("AI 返回了不存在的商品分类，请重试或手动选择", exception.getReason());
    }

    @Test
    void returnsClearTimeoutInsteadOfBlockingPublishingFlow() {
        properties.setRequestTimeout(Duration.ofMillis(50));
        server.createContext("/responses", exchange -> {
            try { Thread.sleep(250); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { respond(exchange, 200, completed("台灯", "描述", 2)); }
            catch (IOException ignored) { }
        });

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> client().generate(new ProductDraftRequest(
                        "台灯", "正常使用的宿舍台灯", ConditionLevel.GOOD)));

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, exception.getStatusCode());
    }

    private OpenAiProductDraftClient client() {
        return new OpenAiProductDraftClient(properties, objectMapper, categories(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build());
    }

    private CategoryMapper categories() {
        return new CategoryMapper() {
            @Override
            public List<CategoryEntity> list() {
                return List.of(category(1L, "教材与书籍", 1), category(2L, "数码与电器", 2));
            }

            @Override
            public int countById(long id) {
                return id == 1 || id == 2 ? 1 : 0;
            }
        };
    }

    private static CategoryEntity category(long id, String name, int sortOrder) {
        CategoryEntity category = new CategoryEntity();
        category.setId(id);
        category.setName(name);
        category.setSortOrder(sortOrder);
        return category;
    }

    private String completed(String title, String description, long categoryId) {
        String draft = objectMapper.writeValueAsString(new Draft(title, description, categoryId));
        return objectMapper.writeValueAsString(new Response("completed", List.of(
                new Output("message", List.of(new Content("output_text", draft))))));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Draft(String title, String description, long categoryId) { }
    private record Content(String type, String text) { }
    private record Output(String type, List<Content> content) { }
    private record Response(String status, List<Output> output) { }
}
