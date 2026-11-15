package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.behavior.BehaviorEvent;
import com.campus.campus_trade_backend.behavior.BehaviorKafkaConfig;
import com.campus.campus_trade_backend.behavior.BehaviorRedisStore;
import com.campus.campus_trade_backend.behavior.BehaviorRebuildService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "campus.kafka.tests", matches = "true")
@ActiveProfiles("local")
@SpringBootTest(properties = {"campus.kafka.enabled=true", "spring.kafka.listener.concurrency=2"})
class KafkaBehaviorIntegrationTests {
    @Container static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("apache/kafka:3.9.1"));
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        properties.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;
    @Autowired BehaviorRedisStore heat;
    @Autowired BehaviorRebuildService rebuild;
    @Autowired StringRedisTemplate redis;
    @Autowired KafkaListenerEndpointRegistry listeners;

    @Test @Timeout(90)
    void duplicateAndReplayDoNotDoubleCountInEitherConsumerGroup() throws Exception {
        long product = 900000 + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000);
        topicReady();
        BehaviorEvent event = new BehaviorEvent(1, UUID.randomUUID().toString(),
                BehaviorEvent.VIEW, product, Instant.now());
        long previousCount = stats(event);
        String payload = json.writeValueAsString(event);
        send(product, payload);
        send(product, payload);
        await(() -> score(product) == 1 && stats(event) == previousCount + 1);
        send(product, payload); // Kafka 历史重放或 offset 回退时仍只计数一次。
        await(() -> score(product) == 1 && stats(event) == previousCount + 1);
        assertEquals(1, score(product));
        assertEquals(previousCount + 1, stats(event));
    }

    @Test @Timeout(90)
    void separateProductsKeepIndependentHeatAndWindow() throws Exception {
        long base = 800000 + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000);
        topicReady();
        BehaviorEvent first = new BehaviorEvent(1, UUID.randomUUID().toString(),
                BehaviorEvent.VIEW, base, Instant.now());
        BehaviorEvent second = new BehaviorEvent(1, UUID.randomUUID().toString(),
                BehaviorEvent.FAVORITE, base + 1, Instant.now());
        send(base, json.writeValueAsString(first));
        send(base + 1, json.writeValueAsString(second));
        await(() -> score(base) == 1 && score(base + 1) == 1);
        assertEquals(1, score(base));
        assertEquals(1, score(base + 1));
    }

    @Test @Timeout(90)
    void heatConsumerRestartsAndResumesFromCommittedOffset() throws Exception {
        topicReady();
        var heatListener = listeners.getListenerContainers().stream()
                .filter(container -> "campus-heat-v1".equals(container.getGroupId()))
                .findFirst().orElseThrow();
        long product = 700000 + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000);
        heatListener.stop();
        try {
            send(product, json.writeValueAsString(new BehaviorEvent(1, UUID.randomUUID().toString(),
                    BehaviorEvent.VIEW, product, Instant.now())));
            assertEquals(0, score(product));
        } finally {
            heatListener.start();
        }
        await(() -> score(product) == 1);
        assertEquals(1, score(product));
    }

    @Test @Timeout(90)
    void replayRebuildAndConcurrentLiveEventKeepExactCount() throws Exception {
        topicReady();
        long product = 600000 + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000);
        BehaviorEvent historical = new BehaviorEvent(1, UUID.randomUUID().toString(),
                BehaviorEvent.VIEW, product, Instant.now());
        send(product, json.writeValueAsString(historical));
        await(() -> score(product) == 1);

        long replayed = rebuild.rebuild();
        assertTrue(replayed >= 1);
        assertEquals(1, score(product));

        String staging = UUID.randomUUID().toString();
        assertTrue(heat.beginRebuild(staging));
        try {
            BehaviorEvent live = new BehaviorEvent(1, UUID.randomUUID().toString(),
                    BehaviorEvent.FAVORITE, product, Instant.now());
            send(product, json.writeValueAsString(live));
            await(() -> score(product) == 2);
            heat.replay(historical, staging, Instant.now());
            heat.replay(historical, staging, Instant.now());
            assertTrue(heat.finishRebuild(staging));
            assertEquals(2, score(product));
        } finally {
            heat.abortRebuild(staging);
        }
    }

    @Test @Timeout(120)
    void backlogOfTwoHundredEventsDrainsAfterHeatConsumerRestart() throws Exception {
        topicReady();
        var heatListener = listeners.getListenerContainers().stream()
                .filter(container -> "campus-heat-v1".equals(container.getGroupId()))
                .findFirst().orElseThrow();
        long product = 500000 + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000);
        heatListener.stop();
        try {
            List<CompletableFuture<?>> sends = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                BehaviorEvent event = new BehaviorEvent(1, UUID.randomUUID().toString(),
                        BehaviorEvent.VIEW, product, Instant.now());
                sends.add(kafka.send(BehaviorKafkaConfig.TOPIC, String.valueOf(product),
                        json.writeValueAsString(event)));
            }
            CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
            assertEquals(0, score(product));
        } finally {
            heatListener.start();
        }
        long started = System.nanoTime();
        await(() -> score(product) == 200);
        long recoveryMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        System.out.println("KAFKA_BACKLOG_RECOVERY events=200 productPartitionKey=" + product
                + " recoveryMs=" + recoveryMs);
        assertEquals(200, score(product));
    }

    @Test @Timeout(90)
    void malformedEventIsRetriedThenSentToDeadLetterTopic() throws Exception {
        topicReady();
        try (var deadLetters = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "campus-test-dlt-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            deadLetters.subscribe(List.of(BehaviorKafkaConfig.TOPIC + ".DLT"));
            long product = 400000 + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000);
            String malformed = "{invalid-json-" + UUID.randomUUID();
            send(product, malformed);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            boolean received = false;
            while (System.nanoTime() < deadline && !received) {
                for (var record : deadLetters.poll(java.time.Duration.ofSeconds(1))) {
                    if (malformed.equals(record.value())) { received = true; break; }
                }
            }
            assertTrue(received, "Malformed event should reach Kafka DLT after bounded retries");
        }
    }

    private void send(long product, String payload) throws Exception {
        kafka.send(BehaviorKafkaConfig.TOPIC, String.valueOf(product), payload).get(10, TimeUnit.SECONDS);
    }

    private void topicReady() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()))) {
            admin.describeTopics(List.of(BehaviorKafkaConfig.TOPIC)).allTopicNames().get(20, TimeUnit.SECONDS);
        }
    }

    private long score(long product) {
        List<BehaviorRedisStore.HotProduct> top = heat.top(50, Instant.now());
        return top.stream().filter(row -> row.productId() == product)
                .mapToLong(BehaviorRedisStore.HotProduct::score).findFirst().orElse(0);
    }

    private long stats(BehaviorEvent event) {
        Object value = redis.opsForHash().get("campus:behavior:stats:"
                + event.occurredAt().toString().substring(0, 10), event.type());
        return value == null ? 0 : Long.parseLong(value.toString());
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Kafka consumers did not update Redis within 30 seconds");
    }
}
