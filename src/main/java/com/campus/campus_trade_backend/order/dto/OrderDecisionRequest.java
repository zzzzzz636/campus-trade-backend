package com.campus.campus_trade_backend.order.dto;

import jakarta.validation.constraints.Size;

public record OrderDecisionRequest(
        @Size(max = 300, message = "处理备注不能超过300字") String note
) {
}
