package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.auth.dto.LoginRequest;
import com.campus.campus_trade_backend.auth.dto.RegisterRequest;
import com.campus.campus_trade_backend.auth.dto.UserResponse;
import com.campus.campus_trade_backend.auth.service.AuthService;
import com.campus.campus_trade_backend.auth.service.RegistrationService;
import com.campus.campus_trade_backend.auth.verification.LocalConsoleMailSender;
import com.campus.campus_trade_backend.auth.verification.VerificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campustrade_v4_security;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "campus.storage.upload-dir=${java.io.tmpdir}/campus-trade-v4-security-images"
})
@AutoConfigureMockMvc
@Transactional
class V4MultiUserSecurityIntegrationTests {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired VerificationService verificationService;
    @Autowired RegistrationService registrationService;
    @Autowired LocalConsoleMailSender localMail;
    @Autowired AuthService authService;
    @Autowired JwtEncoder jwtEncoder;

    @Test
    void unknownAccountWrongPasswordAndInvalidTokensCannotAccessPrivateApis() throws Exception {
        Session user = register("认证测试用户");

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("missing-" + UUID.randomUUID() + "@example.invalid", "password123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("邮箱或密码错误"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(user.email(), "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("邮箱或密码错误"));

        mvc.perform(get("/api/products/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/products/me").header("Authorization", bearer(expiredToken(user.id()))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/products/me").header("Authorization", bearer(tokenForMissingUser())))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/products/me").header("Authorization", bearer(user.token())))
                .andExpect(status().isOk());
    }

    @Test
    void twoUsersCompleteAFullTradeWithoutCrossAccountManagement() throws Exception {
        Session seller = register("V4 卖家 A");
        Session buyer = register("V4 买家 B");

        long productId = id(mvc.perform(post("/api/products")
                        .header("Authorization", bearer(seller.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("A 发布的校园教材", "18.00")))
                .andExpect(status().isCreated()).andReturn());

        MvcResult upload = mvc.perform(multipart("/api/products/{id}/images", productId)
                        .file(new MockMultipartFile("files", "owner.png", "image/png", PNG))
                        .header("Authorization", bearer(seller.token())))
                .andExpect(status().isCreated()).andReturn();
        long imageId = objectMapper.readTree(upload.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path(0).path("id").asLong();

        mvc.perform(get("/api/products").param("q", "A 发布的校园教材"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(productId));
        mvc.perform(get("/api/products/{id}", productId).header("Authorization", bearer(buyer.token())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sellerId").value(seller.id()));

        mvc.perform(put("/api/products/{id}", productId)
                        .header("Authorization", bearer(buyer.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(editJson(productId, seller.token(), "越权修改", "1.00")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/products/{id}", productId)
                        .header("Authorization", bearer(buyer.token())))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/products/{id}/images", productId)
                        .file(new MockMultipartFile("files", "other.png", "image/png", PNG))
                        .header("Authorization", bearer(buyer.token())))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/products/{productId}/images/{imageId}", productId, imageId)
                        .header("Authorization", bearer(buyer.token())))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/products/{id}", productId)
                        .header("Authorization", bearer(seller.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(editJson(productId, seller.token(), "A 更新后的校园教材", "17.50")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("A 更新后的校园教材"));

        mvc.perform(get("/api/products/me").header("Authorization", bearer(seller.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(productId));
        mvc.perform(get("/api/products/me").header("Authorization", bearer(buyer.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());

        long cancelledOrderId = orderId(productId, buyer.token());
        assertOrderIsolation(seller, buyer, cancelledOrderId);
        assertReservedProductCannotBeManaged(productId, seller.token());
        mvc.perform(post("/api/orders/{id}/cancel", cancelledOrderId)
                        .header("Authorization", bearer(buyer.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(put("/api/products/{id}", productId)
                        .header("Authorization", bearer(seller.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(editJson(productId, seller.token(), "取消后重新编辑", "16.00")))
                .andExpect(status().isOk());

        long completedOrderId = orderId(productId, buyer.token());
        mvc.perform(post("/api/orders/{id}/accept", completedOrderId)
                        .header("Authorization", bearer(seller.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WAITING_HANDOFF"));
        mvc.perform(post("/api/orders/{id}/complete", completedOrderId)
                        .header("Authorization", bearer(buyer.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(get("/api/products/me").header("Authorization", bearer(seller.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("SOLD"));
        mvc.perform(put("/api/products/{id}", productId)
                        .header("Authorization", bearer(seller.token()))
                        .contentType(MediaType.APPLICATION_JSON).content(editJson(productId, seller.token(), "售出后修改", "1.00")))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/products/{id}", productId).header("Authorization", bearer(buyer.token())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/products/{id}", productId).header("Authorization", bearer(seller.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SOLD"));
    }

    private void assertOrderIsolation(Session seller, Session buyer, long orderId) throws Exception {
        mvc.perform(get("/api/orders/sales").header("Authorization", bearer(seller.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(orderId));
        mvc.perform(get("/api/orders/purchases").header("Authorization", bearer(seller.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get("/api/orders/purchases").header("Authorization", bearer(buyer.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(orderId));
        mvc.perform(get("/api/orders/sales").header("Authorization", bearer(buyer.token())))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(post("/api/orders/{id}/cancel", orderId)
                        .header("Authorization", bearer(seller.token())))
                .andExpect(status().isForbidden());
    }

    private void assertReservedProductCannotBeManaged(long productId, String sellerToken) throws Exception {
        mvc.perform(put("/api/products/{id}", productId)
                        .header("Authorization", bearer(sellerToken))
                        .contentType(MediaType.APPLICATION_JSON).content(editJson(productId, sellerToken, "预订中修改", "1.00")))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/products/{id}", productId)
                        .header("Authorization", bearer(sellerToken)))
                .andExpect(status().isConflict());
        mvc.perform(multipart("/api/products/{id}/images", productId)
                        .file(new MockMultipartFile("files", "reserved.png", "image/png", PNG))
                        .header("Authorization", bearer(sellerToken)))
                .andExpect(status().isConflict());
    }

    private long orderId(long productId, String buyerToken) throws Exception {
        MvcResult result = mvc.perform(post("/api/orders")
                        .header("Authorization", bearer(buyerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + productId + ",\"requestKey\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("id").asLong();
    }

    private Session register(String nickname) {
        String email = UUID.randomUUID() + "@example.invalid";
        verificationService.send(email);
        UserResponse user = registrationService.register(new RegisterRequest(
                email, "password123", nickname, localMail.latestCodeForTest(email)));
        String token = authService.login(new LoginRequest(email, "password123")).accessToken();
        return new Session(user.id(), email, token);
    }

    private String expiredToken(long userId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("campus-trade")
                .subject(Long.toString(userId)).issuedAt(now.minusSeconds(600))
                .expiresAt(now.minusSeconds(300)).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private String tokenForMissingUser() {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("campus-trade")
                .subject("999999999").issuedAt(now).expiresAt(now.plusSeconds(600)).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private long id(MvcResult result) {
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        return objectMapper.readTree(body)
                .path("id").asLong();
    }

    private static String bearer(String token) { return "Bearer " + token; }

    private static String loginJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String productJson(String title, String price) {
        return "{\"categoryId\":1,\"title\":\"" + title +
                "\",\"description\":\"V4 多用户权限与交易闭环测试\",\"price\":" + price +
                ",\"conditionLevel\":\"GOOD\"}";
    }

    private String editJson(long productId, String sellerToken, String title, String price) throws Exception {
        MvcResult detail = mvc.perform(get("/api/products/{id}", productId)
                        .header("Authorization", bearer(sellerToken)))
                .andExpect(status().isOk()).andReturn();
        int version = objectMapper.readTree(detail.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("version").asInt();
        return productJson(title, price).replaceFirst("\\{", "{\"version\":" + version + ",");
    }

    private record Session(long id, String email, String token) { }
}
