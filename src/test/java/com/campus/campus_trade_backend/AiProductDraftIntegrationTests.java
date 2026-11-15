package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.auth.dto.LoginRequest;
import com.campus.campus_trade_backend.auth.dto.RegisterRequest;
import com.campus.campus_trade_backend.auth.service.AuthService;
import com.campus.campus_trade_backend.auth.service.RegistrationService;
import com.campus.campus_trade_backend.auth.verification.LocalConsoleMailSender;
import com.campus.campus_trade_backend.auth.verification.VerificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campustrade_aiv3;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "campus.ai.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class AiProductDraftIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired VerificationService verificationService;
    @Autowired RegistrationService registrationService;
    @Autowired LocalConsoleMailSender localMail;
    @Autowired AuthService authService;

    @Test
    void draftRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/ai/product-draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledAiDegradesClearlyAndManualPublishingStillWorks() throws Exception {
        String token = registerAndLogin();

        mvc.perform(post("/api/ai/product-draft")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftBody()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("AI 辅助发布尚未启用，可继续手动填写并发布"));

        mvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryId":1,"title":"手动发布的教材","description":"AI 不可用时仍能正常发布", "price":15.00,"conditionLevel":"GOOD"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber());
    }

    @Test
    void disabledAgentReturnsClearErrorWithoutBlockingMarketplace() throws Exception {
        mvc.perform(post("/api/ai/agent/chat")
                        .header("Authorization", "Bearer " + registerAndLogin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"找一本旧教材\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("AI 助手尚未启用；商品搜索和手动发布仍可正常使用"));
    }

    private String registerAndLogin() {
        String email = UUID.randomUUID() + "@example.invalid";
        verificationService.send(email);
        registrationService.register(new RegisterRequest(
                email, "password123", "AI 测试用户", localMail.latestCodeForTest(email)));
        return authService.login(new LoginRequest(email, "password123")).accessToken();
    }

    private static String draftBody() {
        return """
                {"rawTitle":"高数教材","rawDescription":"用过一个学期，有少量笔记，校内面交","conditionLevel":"GOOD"}
                """;
    }
}
