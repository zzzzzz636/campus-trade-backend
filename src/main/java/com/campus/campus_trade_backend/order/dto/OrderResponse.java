package com.campus.campus_trade_backend.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record OrderResponse(
        long id,
        long productId,
        String productTitle,
        String productCoverImageUrl,
        long buyerId,
        String buyerNickname,
        long sellerId,
        String sellerNickname,
        BigDecimal price,
        String status,
        String fulfillmentType,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
