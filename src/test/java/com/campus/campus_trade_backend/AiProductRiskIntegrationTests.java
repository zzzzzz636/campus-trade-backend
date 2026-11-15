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
        "spring.datasource.url=jdbc:h2:mem:campustrade_airisk;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "campus.ai.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class AiProductRiskIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired VerificationService verificationService;
    @Autowired RegistrationService registrationService;
    @Autowired LocalConsoleMailSender localMail;
    @Autowired AuthService authService;

    @Test
    void riskCheckRequiresAuthenticationAndValidProductInput() throws Exception {
        mvc.perform(post("/api/ai/product-risk").contentType(MediaType.APPLICATION_JSON)
                        .content(body("旧教材", "书页完整，东门面交", "8.00")))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/ai/product-risk").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"旧教材","description":"东门面交","price":-1,"categoryId":1}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void normalLowPriceIsOnlyAHintAndManualPublishingStillWorks() throws Exception {
        String token = bearer();
        mvc.perform(post("/api/ai/product-risk").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("旧教材", "书页完整，东门面交，价格便宜因为毕业清理", "1.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLevel").value("low"))
                .andExpect(jsonPath("$.riskTypes").isEmpty())
                .andExpect(jsonPath("$.needsManualReview").value(false))
                .andExpect(jsonPath("$.analysisMode").value("RULES_ONLY"));

        mvc.perform(post("/api/products").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"categoryId":1,"title":"旧教材","description":"书页完整，东门面交，毕业清理","price":1.00,"conditionLevel":"GOOD"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void prepaymentAndProhibitedContentReceiveReviewAdvice() throws Exception {
        String token = bearer();
        mvc.perform(post("/api/ai/product-risk").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("宿舍台灯", "先交定金，再在校内面交", "30.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLevel").value("high"))
                .andExpect(jsonPath("$.riskTypes[0]").value("ADVANCE_PAYMENT"))
                .andExpect(jsonPath("$.needsManualReview").value(true));

        mvc.perform(post("/api/ai/product-risk").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("电子烟", "全新电子烟，校内面交", "30.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskTypes[0]").value("PROHIBITED_ITEM"))
                .andExpect(jsonPath("$.needsManualReview").value(true));
    }

    @Test
    void selfReportedOriginalPriceIsNotTreatedAsConfirmedFraud() throws Exception {
        mvc.perform(post("/api/ai/product-risk").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("旧耳机", "原价 1000 元，现价低，东门面交", "50.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskLevel").value("medium"))
                .andExpect(jsonPath("$.riskTypes[0]").value("SUSPICIOUS_PRICE"))
                .andExpect(jsonPath("$.needsManualReview").value(false));
    }

    private String bearer() {
        String email = UUID.randomUUID() + "@example.invalid";
        verificationService.send(email);
        registrationService.register(new RegisterRequest(email, "password123", "风险测试用户",
                localMail.latestCodeForTest(email)));
        return "Bearer " + authService.login(new LoginRequest(email, "password123")).accessToken();
    }

    private String body(String title, String description, String price) {
        return "{\"title\":\"" + title + "\",\"description\":\"" + description
                + "\",\"price\":" + price + ",\"categoryId\":1}";
    }
}
