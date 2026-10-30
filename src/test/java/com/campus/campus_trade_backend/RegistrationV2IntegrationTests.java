package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.auth.dto.RegisterRequest;
import com.campus.campus_trade_backend.auth.service.RegistrationService;
import com.campus.campus_trade_backend.auth.verification.LocalConsoleMailSender;
import com.campus.campus_trade_backend.auth.verification.VerificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:campustrade_registrationv2;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
class RegistrationV2IntegrationTests {
    @Autowired VerificationService verificationService;
    @Autowired RegistrationService registrationService;
    @Autowired LocalConsoleMailSender localMail;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    private final List<String> emails = new ArrayList<>();

    @AfterEach
    void removeTestUsers() {
        for (String email : emails) jdbc.update("DELETE FROM app_user WHERE email = ?", email);
    }

    @Test
    void verifiedRegistrationRejectsWrongExpiredShapeAndReplay() throws Exception {
        String email = email();
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, null)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/verification-codes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.deliveryMode").value("LOCAL_CONSOLE"));

        String code = localMail.latestCodeForTest(email);
        String wrong = code.equals("000000") ? "111111" : "000000";
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                () -> registrationService.register(new RegisterRequest(email, "password123", "测试", wrong))).getStatusCode());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, code)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.email").value(email));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(email, code)))
                .andExpect(status().isGone());
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
                () -> verificationService.send(email)).getStatusCode());
    }

    @Test
    void limitsCodeSendingAndWrongAttempts() {
        String email = email();
        verificationService.send(email);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(ResponseStatusException.class,
                () -> verificationService.send(email)).getStatusCode());
        String correct = localMail.latestCodeForTest(email);
        String wrong = correct.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 4; i++) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
                    () -> registrationService.register(new RegisterRequest(email, "password123", "测试", wrong))).getStatusCode());
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, assertThrows(ResponseStatusException.class,
                () -> registrationService.register(new RegisterRequest(email, "password123", "测试", wrong))).getStatusCode());
        assertEquals(HttpStatus.GONE, assertThrows(ResponseStatusException.class,
                () -> registrationService.register(new RegisterRequest(email, "password123", "测试", correct))).getStatusCode());
    }

    private String email() {
        String value = UUID.randomUUID() + "@example.invalid";
        emails.add(value);
        return value;
    }

    private static String registerJson(String email, String code) {
        return "{\"email\":\"" + email + "\",\"password\":\"password123\",\"nickname\":\"测试\"" +
                (code == null ? "}" : ",\"code\":\"" + code + "\"}");
    }
}
