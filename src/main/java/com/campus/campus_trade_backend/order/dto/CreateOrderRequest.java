package com.campus.campus_trade_backend.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateOrderRequest(
        @NotNull @Min(1) Long productId,
        @Pattern(regexp = "[A-Za-z0-9_-]{8,64}", message = "请求标识格式不正确") String requestKey
) {
}
