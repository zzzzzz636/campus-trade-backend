package com.campus.campus_trade_backend.auth.dto;

public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds) {
}
