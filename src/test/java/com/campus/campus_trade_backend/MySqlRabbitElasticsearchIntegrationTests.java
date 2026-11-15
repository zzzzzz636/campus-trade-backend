package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.order.outbox.OrderRabbitConfig;
import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.dto.UpdateProductRequest;
import com.campus.campus_trade_backend.product.search.ElasticsearchProductIndex;
import com.campus.campus_trade_backend.product.search.ProductSearchOutboxPublisher;
import com.campus.campus_trade_backend.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "campus.search.tests", matches = "true")
@ActiveProfiles("mysql")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "CAMPUS_DB_PASSWORD=integration-test-only",
        "CAMPUS_JWT_SECRET=QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=",
        "campus.rabbit.enabled=true", "campus.search.enabled=true",
        "campus.rabbit.scan-ms=600000", "campus.search.scan-ms=600000"
})
class MySqlRabbitElasticsearchIntegrationTests {
    @Container @ServiceConnection static final MySQLContainer MYSQL =
            new MySQLContainer(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("campus_trade").withUsername("campus_trade")
                    .withPassword("integration-test-only");
    @Container static final GenericContainer<?> RABBIT = new GenericContainer<>(
            DockerImageName.parse("rabbitmq:4.1-management-alpine"))
            .withExposedPorts(5672)
            .withEnv("RABBITMQ_DEFAULT_USER", "campus_test")
            .withEnv("RABBITMQ_DEFAULT_PASS", "integration-test-only");
    @Container static final GenericContainer<?> ELASTIC = new GenericContainer<>(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:8.17.10"))
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        registry.add("spring.rabbitmq.username", () -> "campus_test");
        registry.add("spring.rabbitmq.password", () -> "integration-test-only");
        registry.add("campus.search.url", () -> "http://" + ELASTIC.getHost() + ":" + ELASTIC.getMappedPort(9200));
    }

    @Autowired ProductService products;
    @Autowired ProductSearchOutboxPublisher publisher;
    @Autowired ElasticsearchProductIndex index;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test @Timeout(180)
    void searchVersionsStatusChangesRebuildAndRecoveryUseRealServices() throws Exception {
        long seller = user();
        long product = products.create(new CreateProductRequest(1L, "Mechanical Keyboard Campus",
                "Used mechanical keyboard", new BigDecimal("200.00"), ConditionLevel.GOOD), seller);
        long createdEvent = event(product, 0);
        products.update(product, seller, new UpdateProductRequest(0, 1L, "Mechanical Keyboard Updated",
                "Used keyboard with cable", new BigDecimal("180.00"), ConditionLevel.GOOD));
        long updatedEvent = event(product, 1);

        // 故意先投递新事件，再投递旧事件和重复事件。
        send(updatedEvent);
        await(() -> consumed(updatedEvent), 30);
        send(createdEvent);
        send(updatedEvent);
        await(() -> consumed(createdEvent), 30);
        refresh();
        assertEquals(1, index.search("Updated", 1L, new BigDecimal("100"),
                new BigDecimal("190"), 1, 10).total());
        assertEquals(0, index.search("Campus", null, null, null, 1, 10).total());
        mvc.perform(get("/api/products/search?q=Updated&categoryId=1&minPrice=100&maxPrice=190"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(product))
                .andExpect(jsonPath("$.items[0].title").value("Mechanical Keyboard Updated"));

        products.takeDown(product, seller);
        publisher.dispatch();
        await(() -> consumed(event(product, 2)), 30);
        refresh();
        assertEquals(0, index.search("Updated", null, null, null, 1, 10).total());

        products.relist(product, seller);
        publisher.dispatch();
        await(() -> consumed(event(product, 3)), 30);
        refresh();
        assertEquals(1, index.search("Updated", null, null, null, 1, 10).total());

        index.rebuild();
        refresh();
        assertEquals(1, index.search("Updated", null, null, null, 1, 10).total());

        products.update(product, seller, new UpdateProductRequest(3, 1L, "Mechanical Keyboard Recovered",
                "Recovered index write", new BigDecimal("170.00"), ConditionLevel.GOOD));
        long failedEvent = event(product, 4);
        settings("{\"index.blocks.write\":true}");
        publisher.dispatch();
        await(() -> rabbit.receive(OrderRabbitConfig.PRODUCT_DEAD_QUEUE, 200) != null, 30);
        assertFalse(consumed(failedEvent));
        settings("{\"index.blocks.write\":false}");
        jdbc.update("UPDATE product_search_outbox SET next_attempt_at = "
                + "DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND) WHERE id = ?", failedEvent);
        publisher.dispatch();
        await(() -> consumed(failedEvent), 30);
        refresh();
        assertEquals(1, index.search("Recovered", null, null, null, 1, 10).total());
    }

    @Test @Timeout(120)
    void queuedEventIsConsumedAfterListenerRestart() throws Exception {
        var listener = listeners.getListenerContainers().stream()
                .filter(container -> container instanceof AbstractMessageListenerContainer rabbitContainer
                        && java.util.Arrays.asList(rabbitContainer.getQueueNames())
                            .contains(OrderRabbitConfig.PRODUCT_QUEUE))
                .findFirst().orElseThrow();
        listener.stop();
        try {
            long seller = user();
            long product = products.create(new CreateProductRequest(1L, "Dormitory Lamp Restart",
                    "Lamp for study", new BigDecimal("45.00"), ConditionLevel.GOOD), seller);
            long eventId = event(product, 0);
            publisher.dispatch();
            assertEquals("PUBLISHED", jdbc.queryForObject(
                    "SELECT status FROM product_search_outbox WHERE id = ?", String.class, eventId));
            assertFalse(consumed(eventId));
            listener.start();
            await(() -> consumed(eventId), 30);
            refresh();
            assertEquals(1, index.search("Dormitory", null, null, null, 1, 10).total());
        } finally {
            if (!listener.isRunning()) listener.start();
        }
    }

    @Test @Timeout(120)
    void productEventRetriesAfterBrokerOutage() throws Exception {
        long seller = user();
        long product = products.create(new CreateProductRequest(1L, "Campus Bicycle Broker Recovery",
                "Used campus bicycle", new BigDecimal("150.00"), ConditionLevel.GOOD), seller);
        long eventId = event(product, 0);
        RABBIT.execInContainer("rabbitmqctl", "stop_app");
        try {
            publisher.dispatch();
            assertEquals("RETRY", jdbc.queryForObject(
                    "SELECT status FROM product_search_outbox WHERE id = ?", String.class, eventId));
            assertFalse(consumed(eventId));
        } finally {
            RABBIT.execInContainer("rabbitmqctl", "start_app");
        }
        jdbc.update("UPDATE product_search_outbox SET next_attempt_at = "
                + "DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 SECOND) WHERE id = ?", eventId);
        publisher.dispatch();
        await(() -> consumed(eventId), 30);
        refresh();
        assertEquals(1, index.search("Bicycle", null, null, null, 1, 10).total());
    }

    private long user() {
        String email = UUID.randomUUID() + "@example.invalid";
        jdbc.update("INSERT INTO app_user (email, password_hash, nickname, status, created_at) "
                + "VALUES (?, 'integration-test-only', '卖家', 'ACTIVE', CURRENT_TIMESTAMP)", email);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    }
    private long event(long product, int version) {
        return jdbc.queryForObject("SELECT id FROM product_search_outbox WHERE product_id = ? AND product_version = ?",
                Long.class, product, version);
    }
    private boolean consumed(long event) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_search_consumed WHERE event_id = ?",
                Long.class, event) == 1;
    }
    private void send(long event) {
        rabbit.convertAndSend(OrderRabbitConfig.PRODUCT_EXCHANGE, "sync", String.valueOf(event));
    }
    private void refresh() throws Exception { http("POST", "/campus-products/_refresh", null); }
    private void settings(String body) throws Exception { http("PUT", "/campus-products/_settings", body); }
    private void http(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://" + ELASTIC.getHost() + ":"
                        + ELASTIC.getMappedPort(9200) + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                "Elasticsearch HTTP " + response.statusCode() + " " + response.body());
    }
    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < until) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        fail("等待索引事件完成超时");
    }
}
