package com.campus.campus_trade_backend.product.dto;

import java.util.List;

public record ProductsPageResponse(
        List<ProductSummaryResponse> items,
        int page,
        int size,
        long total
) {
}
