package com.campus.campus_trade_backend.auth.verification;

public interface VerificationCodeStore {
    enum Result { VALID, MISSING, INVALID, LOCKED }

    void save(String emailKey, String codeDigest);

    Result consume(String emailKey, String codeDigest);

    void clear(String emailKey);
}
