package com.campus.campus_trade_backend.auth.controller;

import com.campus.campus_trade_backend.auth.dto.LoginRequest;
import com.campus.campus_trade_backend.auth.dto.LoginResponse;
import com.campus.campus_trade_backend.auth.dto.RegisterRequest;
import com.campus.campus_trade_backend.auth.dto.UserResponse;
import com.campus.campus_trade_backend.auth.dto.SendVerificationCodeRequest;
import com.campus.campus_trade_backend.auth.dto.VerificationCodeResponse;
import com.campus.campus_trade_backend.auth.service.AuthService;
import com.campus.campus_trade_backend.auth.service.RegistrationService;
import com.campus.campus_trade_backend.auth.verification.VerificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final RegistrationService registrationService;
    private final VerificationService verificationService;

    public AuthController(AuthService authService, RegistrationService registrationService,
                          VerificationService verificationService) {
        this.authService = authService;
        this.registrationService = registrationService;
        this.verificationService = verificationService;
    }

    @PostMapping("/verification-codes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public VerificationCodeResponse sendCode(@Valid @RequestBody SendVerificationCodeRequest request) {
        return verificationService.send(request.email());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return registrationService.register(request);
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return authService.getCurrent(Long.parseLong(jwt.getSubject()));
    }
}
