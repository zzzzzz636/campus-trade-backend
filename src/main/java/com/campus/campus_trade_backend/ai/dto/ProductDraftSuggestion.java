package com.campus.campus_trade_backend.ai.dto;

public record ProductDraftSuggestion(
        String title,
        String description,
        long categoryId,
        String categoryName
) {
}
