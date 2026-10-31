package com.campus.campus_trade_backend.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateOrderRequest(
        @NotNull @Min(1) Long productId,
        @Pattern(regexp = "[A-Za-z0-9_-]{8,64}", message = "请求标识格式不正确") String requestKey,
        FulfillmentType fulfillmentType,
        @Size(max = 500, message = "备注不能超过500字") String buyerNote,
        @Size(max = 200, message = "面交地点不能超过200字") String handoffLocation,
        @Size(max = 80, message = "收件人不能超过80字") String recipientName,
        @Size(max = 30, message = "联系电话不能超过30字") String recipientPhone,
        @Size(max = 500, message = "收货地址不能超过500字") String shippingAddress
) {
}
