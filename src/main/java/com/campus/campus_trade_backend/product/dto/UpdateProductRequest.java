package com.campus.campus_trade_backend.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdateProductRequest(
        @NotNull @Min(0) Integer version,
        @NotNull @Min(1) Long categoryId,
        @NotBlank @Size(max = 120) String title,
        @NotBlank @Size(max = 5000) String description,
        @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal price,
        @NotNull ConditionLevel conditionLevel
) {
}
