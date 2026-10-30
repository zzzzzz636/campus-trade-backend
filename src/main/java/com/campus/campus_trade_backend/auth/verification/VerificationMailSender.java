package com.campus.campus_trade_backend.auth.verification;

public interface VerificationMailSender {
    String send(String email, String code);
}
