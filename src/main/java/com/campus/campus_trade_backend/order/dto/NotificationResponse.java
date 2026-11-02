package com.campus.campus_trade_backend.order.dto;

import java.time.LocalDateTime;

public record NotificationResponse(long id, String type, long referenceId, String title,
                                   LocalDateTime createdAt, LocalDateTime readAt) { }
