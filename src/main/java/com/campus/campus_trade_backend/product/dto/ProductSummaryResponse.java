package com.campus.campus_trade_backend.product.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProductSummaryResponse(
        Long id,
        Long categoryId,
        String categoryName,
        String title,
        BigDecimal price,
        String condition,
        String sellerNickname,
        String coverImageUrl,
        LocalDateTime createdAt
) {
}
