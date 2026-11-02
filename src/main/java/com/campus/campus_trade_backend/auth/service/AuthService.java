package com.campus.campus_trade_backend.auth.service;

import com.campus.campus_trade_backend.auth.dto.LoginRequest;
import com.campus.campus_trade_backend.auth.dto.LoginResponse;
import com.campus.campus_trade_backend.auth.dto.RegisterRequest;
import com.campus.campus_trade_backend.auth.dto.UserResponse;
import com.campus.campus_trade_backend.auth.verification.VerificationService;
import com.campus.campus_trade_backend.auth.entity.UserEntity;
import com.campus.campus_trade_backend.auth.mapper.UserMapper;
import com.campus.campus_trade_backend.auth.security.JwtService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@Service
public class AuthService {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final VerificationService verificationService;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService,
                       VerificationService verificationService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.verificationService = verificationService;
    }

    UserResponse createVerifiedUser(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userMapper.findByEmail(email) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "邮箱已注册");
        }
        UserEntity user = new UserEntity();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname().trim());
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "邮箱已注册", e);
        }
        return toResponse(user);
    }

    public LoginResponse login(LoginRequest request) {
        UserEntity user = userMapper.findByEmail(normalizeEmail(request.email()));
        if (user == null || !"ACTIVE".equals(user.getStatus()) ||
                !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误");
        }
        return jwtService.issue(user.getId());
    }

    public UserResponse getCurrent(long userId) {
        UserEntity user = userMapper.findById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不可用");
        }
        return toResponse(user);
    }

    public UserResponse updateNickname(long userId, String nickname) {
        if (userMapper.updateNickname(userId, nickname.trim()) != 1) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不可用");
        }
        return getCurrent(userId);
    }

    @Transactional
    public void changePassword(long userId, String oldPassword, String newPassword) {
        UserEntity user = userMapper.findById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号不可用");
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原密码错误");
        }
        if (userMapper.changePassword(userId, user.getPasswordHash(), passwordEncoder.encode(newPassword)) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "密码已被修改，请重新登录");
        }
    }

    @Transactional
    public void resetPassword(String rawEmail, String code, String newPassword) {
        String email = normalizeEmail(rawEmail);
        UserEntity user = userMapper.findByEmail(email);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在或不可用");
        }
        verificationService.verifyResetAndConsume(email, code);
        if (userMapper.updatePassword(user.getId(), passwordEncoder.encode(newPassword)) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "重置密码失败");
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static UserResponse toResponse(UserEntity user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getNickname());
    }
}
