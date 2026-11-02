package com.campus.campus_trade_backend.order.dto;

import java.time.LocalDateTime;

public record CancellationRequestResponse(long id, long orderId, long requesterId, String status,
                                          String reason, LocalDateTime createdAt, LocalDateTime resolvedAt) { }
