package com.campus.campus_trade_backend.ai.dto;

import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProductDraftRequest(
        @Size(max = 120) String rawTitle,
        @NotBlank @Size(max = 2000) String rawDescription,
        @NotNull ConditionLevel conditionLevel
) {
}
