package com.campus.campus_trade_backend.order.outbox;

import com.campus.campus_trade_backend.order.mapper.NotificationMapper;
import com.campus.campus_trade_backend.order.mapper.OrderOutboxMapper;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "campus.rabbit.enabled", havingValue = "true")
public class OrderNotificationConsumer {
    private final OrderOutboxMapper outbox;
    private final NotificationMapper notifications;

    public OrderNotificationConsumer(OrderOutboxMapper outbox, NotificationMapper notifications) {
        this.outbox = outbox;
        this.notifications = notifications;
    }

    @RabbitListener(queues = OrderRabbitConfig.QUEUE)
    @Transactional
    public void consume(String rawEventId) {
        long id = Long.parseLong(rawEventId);
        var event = outbox.find(id);
        if (event == null || !"ORDER_APPLICATION".equals(event.eventType())) {
            throw new IllegalArgumentException("Unknown order notification event");
        }
        // 同一数据库事务内先占用事件 ID，再写站内通知；重投递只会影响这一条通知。
        if (outbox.markConsumed(id) == 1) {
            notifications.insert(event.recipientId(), event.eventType(), event.orderId(), event.title());
        }
    }
}
