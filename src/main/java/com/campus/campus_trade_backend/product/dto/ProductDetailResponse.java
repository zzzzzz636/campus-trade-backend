package com.campus.campus_trade_backend.product.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record ProductDetailResponse(
        Long id,
        Long sellerId,
        String sellerNickname,
        Long categoryId,
        String categoryName,
        String title,
        String description,
        BigDecimal price,
        String condition,
        String status,
        List<ProductImageResponse> images,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
