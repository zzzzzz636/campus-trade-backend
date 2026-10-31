package com.campus.campus_trade_backend.messaging.dto;

import java.time.LocalDateTime;

public record ConversationResponse(
        long id,
        String contextType,
        long contextId,
        String contextTitle,
        long otherUserId,
        String otherNickname,
        String lastMessage,
        LocalDateTime lastMessageAt,
        long unreadCount,
        LocalDateTime updatedAt
) {
}
