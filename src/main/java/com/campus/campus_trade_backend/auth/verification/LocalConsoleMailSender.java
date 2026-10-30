package com.campus.campus_trade_backend.auth.verification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("local")
public class LocalConsoleMailSender implements VerificationMailSender {
    private static final Logger log = LoggerFactory.getLogger(LocalConsoleMailSender.class);
    private final Map<String, String> latestCodes = new ConcurrentHashMap<>();

    @Override
    public String send(String email, String code) {
        latestCodes.put(email, code);
        log.info("仅 local 测试邮箱：收件人={}，验证码={}（不会发送真实邮件）", email, code);
        return "LOCAL_CONSOLE";
    }

    public String latestCodeForTest(String email) { return latestCodes.get(email); }
}
