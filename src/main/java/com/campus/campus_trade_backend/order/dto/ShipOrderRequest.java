package com.campus.campus_trade_backend.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShipOrderRequest(
        @NotBlank(message = "请填写快递公司") @Size(max = 80) String carrier,
        @NotBlank(message = "请填写运单号") @Size(max = 100) String trackingNumber
) {
}
