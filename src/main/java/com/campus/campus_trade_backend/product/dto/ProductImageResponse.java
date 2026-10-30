package com.campus.campus_trade_backend.product.dto;

public record ProductImageResponse(
        Long id,
        String url,
        String originalName,
        String contentType,
        Long fileSize,
        Integer sortOrder
) {
}
