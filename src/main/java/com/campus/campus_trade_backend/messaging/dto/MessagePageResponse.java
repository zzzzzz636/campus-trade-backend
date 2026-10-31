package com.campus.campus_trade_backend.messaging.dto;

import java.util.List;

public record MessagePageResponse(
        List<MessageResponse> items,
        Long nextBeforeId,
        boolean hasMore
) {
}
