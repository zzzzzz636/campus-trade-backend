package com.campus.campus_trade_backend.auth.security;

import com.campus.campus_trade_backend.auth.dto.LoginResponse;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
public class JwtService {
    private static final Duration LIFETIME = Duration.ofHours(2);
    private final JwtEncoder encoder;

    public JwtService(JwtEncoder encoder) {
        this.encoder = encoder;
    }

    public LoginResponse issue(long userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("campus-trade")
                .subject(Long.toString(userId))
                .issuedAt(now)
                .expiresAt(now.plus(LIFETIME))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new LoginResponse(token, "Bearer", LIFETIME.toSeconds());
    }
}
