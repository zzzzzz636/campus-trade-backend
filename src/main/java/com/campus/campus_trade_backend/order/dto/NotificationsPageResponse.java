package com.campus.campus_trade_backend.order.dto;

import java.util.List;

public record NotificationsPageResponse(List<NotificationResponse> items, long total, int page,
                                        int size, boolean hasMore) { }
