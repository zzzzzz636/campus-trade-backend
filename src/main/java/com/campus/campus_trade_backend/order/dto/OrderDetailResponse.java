package com.campus.campus_trade_backend.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailResponse(
        long id,
        long productId,
        String productTitle,
        String productDescription,
        String productCoverImageUrl,
        BigDecimal price,
        String status,
        String fulfillmentType,
        long buyerId,
        String buyerNickname,
        long sellerId,
        String sellerNickname,
        String viewerRole,
        String buyerNote,
        String handoffLocation,
        String recipientName,
        String recipientPhone,
        String shippingAddress,
        String shippingCarrier,
        String trackingNumber,
        String sellerDecisionNote,
        LocalDateTime expiresAt,
        LocalDateTime acceptedAt,
        LocalDateTime rejectedAt,
        LocalDateTime shippedAt,
        LocalDateTime completedAt,
        LocalDateTime cancelledAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<OrderEventResponse> events
) {
}
