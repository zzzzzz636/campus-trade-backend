package com.campus.campus_trade_backend.auth.verification;

import com.campus.campus_trade_backend.auth.dto.VerificationCodeResponse;
import com.campus.campus_trade_backend.auth.mapper.UserMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class VerificationService {
    private final VerificationCodeStore store;
    private final VerificationMailSender mailSender;
    private final UserMapper userMapper;
    private final SecretKeySpec digestKey;
    private final SecureRandom random = new SecureRandom();

    public VerificationService(VerificationCodeStore store, VerificationMailSender mailSender,
                               UserMapper userMapper, SecretKey jwtSecretKey) {
        this.store = store;
        this.mailSender = mailSender;
        this.userMapper = userMapper;
        // 从 JWT 主密钥派生独立用途的 HMAC 密钥，Redis 中不保存可离线枚举的六位明文码。
        this.digestKey = new SecretKeySpec(hmac(jwtSecretKey, "campus-trade-otp-v1"), "HmacSHA256");
    }

    public VerificationCodeResponse send(String rawEmail) {
        return sendCode(rawEmail, "register");
    }

    public VerificationCodeResponse sendForPasswordReset(String rawEmail) {
        return sendCode(rawEmail, "reset");
    }

    private VerificationCodeResponse sendCode(String rawEmail, String purpose) {
        String email = normalizeEmail(rawEmail);
        var user = userMapper.findByEmail(email);
        if ("register".equals(purpose) && user != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "邮箱已注册");
        if ("reset".equals(purpose) && (user == null || !"ACTIVE".equals(user.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在或不可用");
        }
        String key = hmacHex(purpose + ":email:" + email);
        String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        try {
            store.save(key, hmacHex(purpose + ":code:" + email + ":" + code));
        } catch (DataAccessException e) {
            clearQuietly(key);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "验证码服务暂不可用", e);
        }
        try {
            String mode = mailSender.send(email, code);
            return new VerificationCodeResponse(mode, 300);
        } catch (RuntimeException e) {
            clearQuietly(key);
            if (e instanceof ResponseStatusException) throw e;
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "邮件发送失败，请稍后重试", e);
        }
    }

    public void verifyAndConsume(String rawEmail, String code) {
        verifyAndConsume(rawEmail, code, "register");
    }

    public void verifyResetAndConsume(String rawEmail, String code) {
        verifyAndConsume(rawEmail, code, "reset");
    }

    private void verifyAndConsume(String rawEmail, String code, String purpose) {
        if (code == null || !code.matches("[0-9]{6}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码应为六位数字");
        }
        String email = normalizeEmail(rawEmail);
        VerificationCodeStore.Result result;
        try {
            result = store.consume(hmacHex(purpose + ":email:" + email), hmacHex(purpose + ":code:" + email + ":" + code));
        } catch (DataAccessException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "验证码服务暂不可用", e);
        }
        switch (result) {
            case VALID -> { return; }
            case MISSING -> throw new ResponseStatusException(HttpStatus.GONE, "验证码已过期或已使用");
            case INVALID -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码错误");
            case LOCKED -> throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "验证码错误次数过多，请重新获取");
        }
    }

    private void clearQuietly(String key) {
        try { store.clear(key); } catch (RuntimeException ignored) { /* 原始错误优先返回，验证码仍有 TTL。 */ }
    }

    private String hmacHex(String value) {
        return HexFormat.of().formatHex(hmac(digestKey, value));
    }

    private static byte[] hmac(SecretKey key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法初始化验证码摘要", e);
        }
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
