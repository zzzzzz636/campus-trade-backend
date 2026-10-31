package com.campus.campus_trade_backend.messaging.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record StartConversationRequest(
        @NotNull ConversationContextType contextType,
        @NotNull @Min(1) Long contextId
) {
}
