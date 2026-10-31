package com.campus.campus_trade_backend.order.dto;

import java.time.LocalDateTime;

public record OrderEventResponse(
        long id,
        Long actorId,
        String eventType,
        String fromStatus,
        String toStatus,
        String note,
        LocalDateTime createdAt
) {
}
