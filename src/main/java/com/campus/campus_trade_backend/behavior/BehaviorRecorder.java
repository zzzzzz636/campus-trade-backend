package com.campus.campus_trade_backend.behavior;

import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Gauge;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class BehaviorRecorder {
    private static final Logger log = LoggerFactory.getLogger(BehaviorRecorder.class);
    // 非关键行为只占用独立有界队列；Kafka 故障时丢弃采集，浏览与收藏仍正常返回。
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(512), runnable -> {
                Thread thread = new Thread(runnable, "behavior-kafka-producer");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final boolean enabled;

    public BehaviorRecorder(KafkaTemplate<String, String> kafka, ObjectMapper json,
                            MeterRegistry metrics, @Value("${campus.kafka.enabled:false}") boolean enabled) {
        this.kafka = kafka;
        this.json = json;
        this.metrics = metrics;
        this.enabled = enabled;
        Gauge.builder("campus.kafka.producer.queue", executor, pool -> pool.getQueue().size())
                .register(metrics);
    }

    public void view(long productId) { record(BehaviorEvent.VIEW, productId); }

    public void favorite(long productId) { record(BehaviorEvent.FAVORITE, productId); }

    private void record(String type, long productId) {
        if (!enabled) return;
        BehaviorEvent event = new BehaviorEvent(BehaviorEvent.CURRENT_SCHEMA,
                UUID.randomUUID().toString(), type, productId, Instant.now());
        try {
            executor.execute(() -> publish(event));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            metrics.counter("campus.kafka.producer.dropped", "reason", "queue_full").increment();
        }
    }

    private void publish(BehaviorEvent event) {
        try {
            // 相同商品进入同一分区，单分区内事件有序；不同商品之间不承诺顺序。
            kafka.send(BehaviorKafkaConfig.TOPIC, String.valueOf(event.productId()), json.writeValueAsString(event))
                    .whenComplete((result, failure) -> {
                        if (failure != null) {
                            metrics.counter("campus.kafka.producer.failed").increment();
                            log.warn("Behavior event publish failed type={} productId={}", event.type(), event.productId());
                        } else {
                            metrics.counter("campus.kafka.producer.sent", "type", event.type()).increment();
                        }
                    });
        } catch (RuntimeException failure) {
            metrics.counter("campus.kafka.producer.failed").increment();
            log.warn("Behavior event submit failed type={} productId={}", event.type(), event.productId());
        }
    }

    @PreDestroy
    void shutdown() { executor.shutdown(); }
}
