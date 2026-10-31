package com.campus.campus_trade_backend.messaging.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendMessageRequest(
        @NotBlank(message = "消息不能为空") @Size(max = 1000, message = "消息不能超过1000字") String content
) {
}
