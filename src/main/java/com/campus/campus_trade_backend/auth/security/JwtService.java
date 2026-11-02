package com.campus.campus_trade_backend.auth.security;

import com.campus.campus_trade_backend.auth.dto.LoginResponse;
import com.campus.campus_trade_backend.auth.entity.UserEntity;
import com.campus.campus_trade_backend.auth.mapper.UserMapper;
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
    private final UserMapper userMapper;

    public JwtService(JwtEncoder encoder, UserMapper userMapper) {
        this.encoder = encoder;
        this.userMapper = userMapper;
    }

    public LoginResponse issue(long userId) {
        UserEntity user = userMapper.findById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) throw new IllegalArgumentException("用户不存在或不可用");
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("campus-trade")
                .subject(Long.toString(userId))
                .claim("tv", user.getTokenVersion())
                .issuedAt(now)
                .expiresAt(now.plus(LIFETIME))
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
        return new LoginResponse(token, "Bearer", LIFETIME.toSeconds());
    }
}
