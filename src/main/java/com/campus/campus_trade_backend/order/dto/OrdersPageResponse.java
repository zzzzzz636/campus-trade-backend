package com.campus.campus_trade_backend.order.dto;

import java.util.List;

public record OrdersPageResponse(List<OrderResponse> items, long total, int page, int size, boolean hasMore) { }
