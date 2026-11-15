package com.campus.campus_trade_backend.order.outbox;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "campus.rabbit.enabled", havingValue = "true")
public class OrderRabbitConfig {
    public static final String EXCHANGE = "campus.order.notifications";
    public static final String QUEUE = "campus.order.notifications.seller";
    public static final String DEAD_EXCHANGE = "campus.order.notifications.dead";
    public static final String DEAD_QUEUE = "campus.order.notifications.seller.dead";
    public static final String PRODUCT_EXCHANGE = "campus.product.search";
    public static final String PRODUCT_QUEUE = "campus.product.search.sync";
    public static final String PRODUCT_DEAD_EXCHANGE = "campus.product.search.dead";
    public static final String PRODUCT_DEAD_QUEUE = "campus.product.search.sync.dead";

    @Bean DirectExchange orderNotificationExchange() { return new DirectExchange(EXCHANGE, true, false); }
    @Bean DirectExchange orderNotificationDeadExchange() { return new DirectExchange(DEAD_EXCHANGE, true, false); }
    @Bean Queue orderNotificationQueue() {
        return QueueBuilder.durable(QUEUE).deadLetterExchange(DEAD_EXCHANGE).deadLetterRoutingKey("dead").build();
    }
    @Bean Queue orderNotificationDeadQueue() { return QueueBuilder.durable(DEAD_QUEUE).build(); }
    @Bean Binding orderNotificationBinding() {
        return BindingBuilder.bind(orderNotificationQueue()).to(orderNotificationExchange()).with("seller");
    }
    @Bean Binding orderNotificationDeadBinding() {
        return BindingBuilder.bind(orderNotificationDeadQueue()).to(orderNotificationDeadExchange()).with("dead");
    }
    @Bean DirectExchange productSearchExchange() { return new DirectExchange(PRODUCT_EXCHANGE, true, false); }
    @Bean DirectExchange productSearchDeadExchange() { return new DirectExchange(PRODUCT_DEAD_EXCHANGE, true, false); }
    @Bean Queue productSearchQueue() {
        return QueueBuilder.durable(PRODUCT_QUEUE).deadLetterExchange(PRODUCT_DEAD_EXCHANGE)
                .deadLetterRoutingKey("dead").build();
    }
    @Bean Queue productSearchDeadQueue() { return QueueBuilder.durable(PRODUCT_DEAD_QUEUE).build(); }
    @Bean Binding productSearchBinding() {
        return BindingBuilder.bind(productSearchQueue()).to(productSearchExchange()).with("sync");
    }
    @Bean Binding productSearchDeadBinding() {
        return BindingBuilder.bind(productSearchDeadQueue()).to(productSearchDeadExchange()).with("dead");
    }
    @Bean SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless().maxRetries(2)
                .backOffOptions(1000, 2.0, 5000)
                .recoverer(new RejectAndDontRequeueRecoverer()).build());
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
