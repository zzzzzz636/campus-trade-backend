package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.auth.dto.RegisterRequest;
import com.campus.campus_trade_backend.auth.service.RegistrationService;
import com.campus.campus_trade_backend.auth.verification.VerificationService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ActiveProfiles("docker-test")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campustrade_docker_mail;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/schema.sql",
        "mybatis.configuration.map-underscore-to-camel-case=true",
        "spring.data.redis.url=redis://127.0.0.1:6379",
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=1025",
        "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "campus.mail.from=noreply@campustrade.local",
        "CAMPUS_JWT_SECRET=QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE="
})
class DockerMailRedisIntegrationTests {
    private static final Pattern ID_PATTERN = Pattern.compile("\\\"ID\\\":\\\"([^\\\"]+)\\\"");
    private static final Pattern CODE_PATTERN = Pattern.compile("CampusTrade.*?(\\d{6})", Pattern.DOTALL);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Autowired VerificationService verificationService;
    @Autowired RegistrationService registrationService;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sendsSmtpMailStoresDigestInRedisAndConsumesCode() throws Exception {
        Assumptions.assumeTrue(portOpen(6379) && portOpen(1025) && portOpen(8025),
                "Docker Redis/Mailpit 未运行，跳过外部服务集成测试");
        Set<String> before = messageIds(messages());
        Set<String> keysBefore = keys();
        String email = "docker-" + UUID.randomUUID() + "@example.invalid";

        assertEquals("SMTP", verificationService.send(email).deliveryMode());
        String newId = waitForNewMessage(before);
        String message = get("http://127.0.0.1:8025/api/v1/message/" + newId);
        Matcher matcher = CODE_PATTERN.matcher(message);
        assertTrue(matcher.find(), "Mailpit 邮件中应包含六位验证码");
        String code = matcher.group(1);

        Set<String> createdKeys = keys();
        createdKeys.removeAll(keysBefore);
        String codeKey = createdKeys.stream().filter(key -> key.startsWith("ct:otp:code:")).findFirst().orElse(null);
        assertNotNull(codeKey, "Redis 应保存验证码摘要");
        String stored = redis.opsForValue().get(codeKey);
        assertNotNull(stored);
        assertEquals(64, stored.length());
        assertFalse(stored.contains(code), "Redis 不得保存六位验证码明文");

        var user = registrationService.register(new RegisterRequest(email, "password123", "Docker 邮件测试", code));
        assertEquals(email, user.email());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(codeKey)), "验证码成功使用后应立即删除");
        jdbc.update("DELETE FROM app_user WHERE id = ?", user.id());
    }

    private static boolean portOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String waitForNewMessage(Set<String> before) throws Exception {
        for (int i = 0; i < 20; i++) {
            Set<String> current = messageIds(messages());
            current.removeAll(before);
            if (!current.isEmpty()) return current.iterator().next();
            Thread.sleep(100);
        }
        throw new AssertionError("Mailpit 未收到验证码邮件");
    }

    private static Set<String> messageIds(String json) {
        Set<String> ids = new HashSet<>();
        Matcher matcher = ID_PATTERN.matcher(json);
        while (matcher.find()) ids.add(matcher.group(1));
        return ids;
    }

    private Set<String> keys() {
        Set<String> found = redis.keys("ct:otp:*");
        return found == null ? new HashSet<>() : new HashSet<>(found);
    }

    private static String messages() throws Exception {
        return get("http://127.0.0.1:8025/api/v1/messages");
    }

    private static String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }
}
