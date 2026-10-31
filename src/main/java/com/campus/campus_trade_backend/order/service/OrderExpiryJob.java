package com.campus.campus_trade_backend.order.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderExpiryJob {
    private final OrderService orderService;

    public OrderExpiryJob(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${campus.order.expiry-scan-ms:60000}")
    public void expirePendingApplications() {
        orderService.expirePendingOrders(100);
    }
}
