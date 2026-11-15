package com.campus.campus_trade_backend.product.search;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "campus.search.rebuild-on-startup", havingValue = "true")
public class ProductSearchRebuildRunner implements ApplicationRunner {
    private final ElasticsearchProductIndex index;

    public ProductSearchRebuildRunner(ElasticsearchProductIndex index) {
        this.index = index;
    }

    @Override public void run(ApplicationArguments args) {
        index.rebuild();
    }
}
