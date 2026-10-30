package com.campus.campus_trade_backend.product.dto;

import java.math.BigDecimal;

public record ProductSummaryResponse(
        Long id,
        Long categoryId,
        String title,
        BigDecimal price,
        String condition,
        String sellerNickname,
        String coverImageUrl
) {
}
