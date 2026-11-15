package com.campus.campus_trade_backend.product.search;

import com.campus.campus_trade_backend.order.outbox.OrderRabbitConfig;
import com.campus.campus_trade_backend.product.mapper.ProductSearchOutboxMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = {"campus.rabbit.enabled", "campus.search.enabled"}, havingValue = "true")
public class ProductSearchEventConsumer {
    private final ProductSearchOutboxMapper outbox;
    private final ElasticsearchProductIndex index;

    public ProductSearchEventConsumer(ProductSearchOutboxMapper outbox, ElasticsearchProductIndex index) {
        this.outbox = outbox;
        this.index = index;
    }

    @RabbitListener(queues = OrderRabbitConfig.PRODUCT_QUEUE)
    public void consume(String rawEventId) {
        long id = Long.parseLong(rawEventId);
        var event = outbox.find(id);
        if (event == null) throw new IllegalArgumentException("Unknown product search event");
        if (outbox.consumed(id) != 0) return;
        // 读取 MySQL 最新状态；外部版本控制使乱序消息无法把旧状态写回索引。
        index.indexLatest(event.productId());
        outbox.markConsumed(id);
    }
}
