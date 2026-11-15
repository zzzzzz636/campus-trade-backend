package com.campus.campus_trade_backend.ai.client;

import com.campus.campus_trade_backend.ai.config.AiPrompts;
import com.campus.campus_trade_backend.ai.config.AiProperties;
import com.campus.campus_trade_backend.ai.dto.ProductRiskRequest;
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
import java.math.BigDecimal;
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

class OpenAiProductRiskClientTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private AiProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        properties = new AiProperties();
        properties.setEnabled(true);
        properties.setApiKey("not-a-real-test-key");
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
    void sendsVersionedPromptAndStrictSchemaAndValidatesOutput() {
        AtomicReference<String> sent = new AtomicReference<>();
        server.createContext("/responses", exchange -> {
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, completed("medium", List.of("MISLEADING_CLAIM")));
        });

        var result = client().analyze(request(), "教材与书籍");
        assertEquals("medium", result.riskLevel());
        assertEquals(List.of("MISLEADING_CLAIM"), result.riskTypes());
        JsonNode body = mapper.readTree(sent.get());
        assertEquals("json_schema", body.path("text").path("format").path("type").asString());
        assertTrue(body.path("text").path("format").path("strict").asBoolean());
        assertTrue(body.path("input").get(0).path("content").asString().contains("风险提醒"));
        assertFalse(sent.get().contains("not-a-real-test-key"));
    }

    @Test
    void rejectsInvalidJson() {
        server.createContext("/responses", exchange -> respond(exchange, 200, """
                {"status":"completed","output":[{"content":[{"type":"output_text","text":"not json"}]}]}
                """));
        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(ResponseStatusException.class,
                () -> client().analyze(request(), "教材与书籍")).getStatusCode());
    }

    @Test
    void rejectsUnknownRiskType() {
        server.createContext("/responses", exchange ->
                respond(exchange, 200, completed("high", List.of("NOT_IN_WHITELIST"))));
        assertEquals(HttpStatus.BAD_GATEWAY, assertThrows(ResponseStatusException.class,
                () -> client().analyze(request(), "教材与书籍")).getStatusCode());
    }

    @Test
    void retriesTransient429AndReturnsValidatedResult() {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/responses", exchange -> {
            if (calls.incrementAndGet() == 1) respond(exchange, 429, "");
            else respond(exchange, 200, completed("low", List.of()));
        });
        assertEquals("low", client().analyze(request(), "教材与书籍").riskLevel());
        assertEquals(2, calls.get());
    }

    @Test
    void timeoutIsBoundedAndDoesNotExposeProviderBody() {
        properties.setRequestTimeout(Duration.ofMillis(50));
        server.createContext("/responses", exchange -> {
            try { Thread.sleep(250); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            try { respond(exchange, 200, completed("low", List.of())); }
            catch (IOException ignored) { }
        });
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, assertThrows(ResponseStatusException.class,
                () -> client().analyze(request(), "教材与书籍")).getStatusCode());
    }

    private OpenAiProductRiskClient client() {
        return new OpenAiProductRiskClient(properties, new AiPrompts(),
                new OpenAiResponsesGateway(properties, HttpClient.newHttpClient()), mapper);
    }

    private ProductRiskRequest request() {
        return new ProductRiskRequest("高数教材", "少量笔记，校内面交", new BigDecimal("15.00"), 1L);
    }

    private String completed(String level, List<String> types) {
        String output = mapper.writeValueAsString(new Risk(level, types,
                List.of("请核实卖家描述"), List.of("当面查看实物"), false));
        return mapper.writeValueAsString(new Response("completed",
                List.of(new Output(List.of(new Content("output_text", output))))));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Risk(String riskLevel, List<String> riskTypes, List<String> reasons,
                        List<String> suggestions, boolean needsManualReview) { }
    private record Content(String type, String text) { }
    private record Output(List<Content> content) { }
    private record Response(String status, List<Output> output) { }
}
