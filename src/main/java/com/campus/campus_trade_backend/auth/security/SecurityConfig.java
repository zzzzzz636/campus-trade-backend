package com.campus.campus_trade_backend.auth.security;

import com.campus.campus_trade_backend.auth.entity.UserEntity;
import com.campus.campus_trade_backend.auth.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import jakarta.servlet.DispatcherType;
import java.security.SecureRandom;
import java.util.Base64;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/styles.css", "/app.js", "/js/**", "/favicon.ico").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login", "/api/auth/verification-codes",
                                "/api/auth/password-reset-codes", "/api/auth/password-reset").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/*", "/api/categories").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/product-images/*/content").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecretKey jwtSecretKey(@Value("${CAMPUS_JWT_SECRET:}") String encoded, Environment environment) {
        byte[] key;
        if (encoded.isBlank() && environment.acceptsProfiles(Profiles.of("local"))) {
            key = new byte[32];
            new SecureRandom().nextBytes(key);
        } else if (!encoded.isBlank()) {
            try {
                key = Base64.getDecoder().decode(encoded);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("CAMPUS_JWT_SECRET 必须是 Base64 编码的密钥", e);
            }
        } else {
            throw new IllegalStateException("mysql 配置需要 CAMPUS_JWT_SECRET 环境变量");
        }
        if (key.length < 32) {
            throw new IllegalStateException("JWT 密钥至少需要 32 字节");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSecretKey).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, UserMapper userMapper) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> activeUser = jwt -> {
            try {
                long userId = Long.parseLong(jwt.getSubject());
                UserEntity user = userMapper.findById(userId);
                Object version = jwt.getClaim("tv");
                if (user != null && "ACTIVE".equals(user.getStatus()) && version instanceof Number number
                        && number.intValue() == user.getTokenVersion()) {
                    return OAuth2TokenValidatorResult.success();
                }
            } catch (NumberFormatException ignored) { }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("inactive_user", "账号不存在或已被禁用", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("campus-trade"), activeUser));
        return decoder;
    }
}
