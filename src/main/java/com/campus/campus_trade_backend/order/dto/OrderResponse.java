package com.campus.campus_trade_backend.order.dto;

import java.math.BigDecimal;

public record OrderResponse(
        long id,
        long productId,
        String productTitle,
        long buyerId,
        long sellerId,
        BigDecimal price,
        String status
) {
}
