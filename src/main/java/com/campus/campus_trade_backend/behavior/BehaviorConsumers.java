package com.campus.campus_trade_backend.behavior;

import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Instant;
import java.time.Duration;

@Component
@ConditionalOnProperty(name = "campus.kafka.enabled", havingValue = "true")
public class BehaviorConsumers {
    private final ObjectMapper json;
    private final BehaviorRedisStore store;
    private final MeterRegistry metrics;

    public BehaviorConsumers(ObjectMapper json, BehaviorRedisStore store, MeterRegistry metrics) {
        this.json = json;
        this.store = store;
        this.metrics = metrics;
    }

    @KafkaListener(topics = BehaviorKafkaConfig.TOPIC, groupId = "campus-heat-v1")
    public void heat(String payload, Acknowledgment acknowledgment) throws Exception {
        BehaviorEvent event = BehaviorEvent.parse(json, payload);
        store.applyHeat(event, Instant.now());
        metrics.counter("campus.kafka.consumer.processed", "group", "heat").increment();
        metrics.timer("campus.kafka.consumer.end.to.end", "group", "heat")
                .record(Duration.between(event.occurredAt(), Instant.now()).isNegative()
                        ? Duration.ZERO : Duration.between(event.occurredAt(), Instant.now()));
        acknowledgment.acknowledge();
    }

    @KafkaListener(topics = BehaviorKafkaConfig.TOPIC, groupId = "campus-behavior-stats-v1")
    public void stats(String payload, Acknowledgment acknowledgment) throws Exception {
        BehaviorEvent event = BehaviorEvent.parse(json, payload);
        store.applyStats(event);
        metrics.counter("campus.kafka.consumer.processed", "group", "stats").increment();
        metrics.timer("campus.kafka.consumer.end.to.end", "group", "stats")
                .record(Duration.between(event.occurredAt(), Instant.now()).isNegative()
                        ? Duration.ZERO : Duration.between(event.occurredAt(), Instant.now()));
        acknowledgment.acknowledge();
    }

    @Bean
    DefaultErrorHandler behaviorErrorHandler(KafkaTemplate<String, String> kafka, MeterRegistry metrics) {
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(kafka,
                (record, error) -> new TopicPartition(BehaviorKafkaConfig.TOPIC + ".DLT", record.partition()));
        DefaultErrorHandler handler = new DefaultErrorHandler(deadLetter, new FixedBackOff(1000L, 2));
        handler.setCommitRecovered(true);
        handler.setRetryListeners(new RetryListener() {
            @Override public void failedDelivery(ConsumerRecord<?, ?> record, Exception error, int attempt) {
                metrics.counter("campus.kafka.consumer.failed").increment();
            }
            @Override public void recovered(ConsumerRecord<?, ?> record, Exception error) {
                metrics.counter("campus.kafka.consumer.dead_lettered").increment();
            }
        });
        return handler;
    }
}
