package com.campus.campus_trade_backend.messaging.dto;

import java.time.LocalDateTime;

public record MessageResponse(
        long id,
        long conversationId,
        long senderId,
        String senderNickname,
        String content,
        LocalDateTime sentAt,
        LocalDateTime readAt,
        boolean mine
) {
}
