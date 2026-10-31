package com.campus.campus_trade_backend.favorite.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record FavoriteProductResponse(
        long id,
        long sellerId,
        String sellerNickname,
        String categoryName,
        String title,
        BigDecimal price,
        String condition,
        String status,
        String coverImageUrl,
        LocalDateTime favoritedAt
) {
}
