package com.campus.campus_trade_backend.auth.dto;

public record VerificationCodeResponse(String deliveryMode, int expiresInSeconds) {
}
