package com.campus.campus_trade_backend.product.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MyProductResponse(
        Long id,
        Long categoryId,
        String categoryName,
        String title,
        BigDecimal price,
        String condition,
        String status,
        String coverImageUrl,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
