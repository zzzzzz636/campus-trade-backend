package com.campus.campus_trade_backend.auth.verification;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@Profile("!local")
public class SmtpVerificationMailSender implements VerificationMailSender {
    private final ObjectProvider<JavaMailSender> senders;
    private final String from;

    public SmtpVerificationMailSender(ObjectProvider<JavaMailSender> senders,
                                      @Value("${campus.mail.from:${CAMPUS_SMTP_FROM:}}") String from) {
        this.senders = senders;
        this.from = from;
    }

    @Override
    public String send(String email, String code) {
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null || from.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "邮件服务尚未配置");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("CampusTrade 注册验证码");
        message.setText("你的 CampusTrade 注册验证码是：" + code + "。五分钟内有效，请勿告诉他人。");
        try {
            sender.send(message);
        } catch (MailException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "邮件发送失败，请稍后重试", e);
        }
        return "SMTP";
    }
}
