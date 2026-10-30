package com.campus.campus_trade_backend.product.dto;

import java.math.BigDecimal;

public record MyProductResponse(
        Long id,
        Long categoryId,
        String title,
        BigDecimal price,
        String condition,
        String status,
        String coverImageUrl
) {
}
