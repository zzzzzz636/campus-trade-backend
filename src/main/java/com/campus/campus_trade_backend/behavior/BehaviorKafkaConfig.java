package com.campus.campus_trade_backend.behavior;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "campus.kafka.enabled", havingValue = "true")
public class BehaviorKafkaConfig {
    public static final String TOPIC = "campus.behavior.v1";

    @Bean
    NewTopic behaviorTopic() {
        return TopicBuilder.name(TOPIC).partitions(6).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(72L * 60 * 60 * 1000))
                .build();
    }

    @Bean
    NewTopic behaviorDeadLetterTopic() {
        return TopicBuilder.name(TOPIC + ".DLT").partitions(6).replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(7L * 24 * 60 * 60 * 1000))
                .build();
    }
}
