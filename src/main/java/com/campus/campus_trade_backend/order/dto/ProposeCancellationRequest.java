package com.campus.campus_trade_backend.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProposeCancellationRequest(@NotBlank @Size(max = 300) String reason) { }
