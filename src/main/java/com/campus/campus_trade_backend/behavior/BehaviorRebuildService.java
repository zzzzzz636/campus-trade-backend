package com.campus.campus_trade_backend.behavior;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "campus.kafka.enabled", havingValue = "true")
public class BehaviorRebuildService {
    private static final Duration MAX_TIME = Duration.ofMinutes(10);
    private static final long MAX_EVENTS = 2_000_000;
    private final ConsumerFactory<String, String> factory;
    private final BehaviorRedisStore store;
    private final ObjectMapper json;
    private final MeterRegistry metrics;

    public BehaviorRebuildService(ConsumerFactory<String, String> factory, BehaviorRedisStore store,
                                  ObjectMapper json, MeterRegistry metrics) {
        this.factory = factory;
        this.store = store;
        this.json = json;
        this.metrics = metrics;
    }

    public long rebuild() {
        String generation = UUID.randomUUID().toString();
        if (!store.beginRebuild(generation)) throw new IllegalStateException("Hot ranking rebuild already running");
        long processed = 0;
        Instant deadline = Instant.now().plus(MAX_TIME);
        try (Consumer<String, String> consumer = factory.createConsumer("campus-heat-replay-" + generation)) {
            var infos = consumer.partitionsFor(BehaviorKafkaConfig.TOPIC, Duration.ofSeconds(20));
            if (infos == null || infos.isEmpty()) throw new IllegalStateException("Behavior topic has no partitions");
            List<TopicPartition> partitions = infos.stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            while (!caughtUp(consumer, endOffsets)) {
                if (Instant.now().isAfter(deadline) || processed >= MAX_EVENTS) {
                    throw new IllegalStateException("Hot ranking replay exceeded its safety limit");
                }
                if (!store.renewRebuild(generation)) throw new IllegalStateException("Hot ranking rebuild lease lost");
                for (var record : consumer.poll(Duration.ofSeconds(1))) {
                    if (record.offset() >= endOffsets.get(new TopicPartition(record.topic(), record.partition()))) continue;
                    // 历史事件先写入隔离代际；在线消费同时双写。切换指针与双写在 Redis Lua 中原子执行。
                    store.replay(BehaviorEvent.parse(json, record.value()), generation, Instant.now());
                    processed++;
                }
            }
            if (!store.finishRebuild(generation)) throw new IllegalStateException("Hot ranking rebuild lease lost");
            metrics.counter("campus.kafka.rebuild.completed").increment();
            metrics.counter("campus.kafka.rebuild.events").increment(processed);
            return processed;
        } catch (RuntimeException failure) {
            store.abortRebuild(generation);
            metrics.counter("campus.kafka.rebuild.failed").increment();
            throw failure;
        }
    }

    private static boolean caughtUp(Consumer<String, String> consumer, Map<TopicPartition, Long> endOffsets) {
        for (var entry : endOffsets.entrySet()) {
            if (consumer.position(entry.getKey()) < entry.getValue()) return false;
        }
        return true;
    }
}
