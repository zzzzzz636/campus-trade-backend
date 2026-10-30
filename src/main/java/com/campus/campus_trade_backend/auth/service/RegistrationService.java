package com.campus.campus_trade_backend.auth.service;

import com.campus.campus_trade_backend.auth.dto.RegisterRequest;
import com.campus.campus_trade_backend.auth.dto.UserResponse;
import com.campus.campus_trade_backend.auth.verification.VerificationService;
import org.springframework.stereotype.Service;

@Service
public class RegistrationService {
    private final VerificationService verificationService;
    private final AuthService authService;

    public RegistrationService(VerificationService verificationService, AuthService authService) {
        this.verificationService = verificationService;
        this.authService = authService;
    }

    public UserResponse register(RegisterRequest request) {
        verificationService.verifyAndConsume(request.email(), request.code());
        return authService.createVerifiedUser(request);
    }
}
