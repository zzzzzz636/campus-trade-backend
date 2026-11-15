package com.campus.campus_trade_backend.behavior;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "campus.kafka.rebuild-on-startup", havingValue = "true")
public class BehaviorRebuildRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(BehaviorRebuildRunner.class);
    private final BehaviorRebuildService rebuild;

    public BehaviorRebuildRunner(BehaviorRebuildService rebuild) { this.rebuild = rebuild; }

    @Override
    public void run(ApplicationArguments args) {
        long count = rebuild.rebuild();
        log.info("Kafka hot ranking replay completed events={}", count);
    }
}
