package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.auth.dto.RegisterRequest;
import com.campus.campus_trade_backend.auth.dto.UserResponse;
import com.campus.campus_trade_backend.auth.security.JwtService;
import com.campus.campus_trade_backend.auth.service.RegistrationService;
import com.campus.campus_trade_backend.auth.verification.LocalConsoleMailSender;
import com.campus.campus_trade_backend.auth.verification.VerificationService;
import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.dto.ProductImageResponse;
import com.campus.campus_trade_backend.product.service.ProductImageService;
import com.campus.campus_trade_backend.product.service.ProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:campustrade_imagesv2;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "campus.storage.upload-dir=${java.io.tmpdir}/campus-trade-image-tests"
})
@AutoConfigureMockMvc
class ProductImageV2IntegrationTests {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Autowired ProductImageService imageService;
    @Autowired ProductService productService;
    @Autowired VerificationService verificationService;
    @Autowired RegistrationService registrationService;
    @Autowired LocalConsoleMailSender localMail;
    @Autowired JwtService jwtService;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private final List<Long> productIds = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void removeRows() {
        for (long productId : productIds) jdbc.update("DELETE FROM product_image WHERE product_id = ?", productId);
        for (long productId : productIds) jdbc.update("DELETE FROM product_search_consumed WHERE event_id IN (SELECT id FROM product_search_outbox WHERE product_id = ?)", productId);
        for (long productId : productIds) jdbc.update("DELETE FROM product_search_outbox WHERE product_id = ?", productId);
        for (long productId : productIds) jdbc.update("DELETE FROM product WHERE id = ?", productId);
        for (long userId : userIds) jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    @Test
    void ownerCanUploadReadAndDeleteARealImage() throws Exception {
        UserResponse seller = user("图片卖家");
        long productId = product(seller.id());
        MockMultipartFile image = new MockMultipartFile("files", "cover.png", "text/plain", PNG);

        var uploaded = imageService.upload(productId, seller.id(), List.of(image));
        assertEquals(1, uploaded.size());
        assertEquals("image/png", uploaded.get(0).contentType());
        assertTrue(imageService.content(uploaded.get(0).id()).resource().exists());
        assertEquals(uploaded.get(0).url(), productService.findById(productId).orElseThrow().images().get(0).url());

        Path stored = imageService.content(uploaded.get(0).id()).resource().getFile().toPath();
        imageService.delete(productId, uploaded.get(0).id(), seller.id());
        assertFalse(Files.exists(stored));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id = ?", Integer.class, productId));
    }

    @Test
    void uploadingThreeImagesAssignsUniqueOrders() {
        UserResponse seller = user("多图卖家");
        long productId = product(seller.id());

        assertOrders(productId, upload(productId, seller.id(), 3), List.of(0, 1, 2));
    }

    @Test
    void deletingMiddleImageDoesNotReuseAnOccupiedOrder() {
        UserResponse seller = user("中间删除卖家");
        long productId = product(seller.id());
        List<ProductImageResponse> original = upload(productId, seller.id(), 3);

        imageService.delete(productId, original.get(1).id(), seller.id());
        assertOrders(productId, upload(productId, seller.id(), 1), List.of(0, 2, 3));
    }

    @Test
    void deletingLastImageAllowsAnotherUpload() {
        UserResponse seller = user("末尾删除卖家");
        long productId = product(seller.id());
        List<ProductImageResponse> original = upload(productId, seller.id(), 3);

        imageService.delete(productId, original.get(2).id(), seller.id());
        assertOrders(productId, upload(productId, seller.id(), 1), List.of(0, 1, 2));
    }

    @Test
    void rejectsDisguisedFilesAndOwnershipViolations() {
        UserResponse seller = user("卖家");
        UserResponse other = user("其他人");
        long productId = product(seller.id());
        MockMultipartFile fake = new MockMultipartFile("files", "fake.png", "image/png", "not an image".getBytes());

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, assertThrows(ResponseStatusException.class,
                () -> imageService.upload(productId, seller.id(), List.of(fake))).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
                () -> imageService.upload(productId, other.id(), List.of(
                        new MockMultipartFile("files", "ok.png", "image/png", PNG)))).getStatusCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product_image WHERE product_id = ?", Integer.class, productId));
    }

    @Test
    void multipartHttpRouteRequiresJwtOwner() throws Exception {
        UserResponse seller = user("接口卖家");
        UserResponse other = user("接口其他人");
        long productId = product(seller.id());
        MockMultipartFile image = new MockMultipartFile("files", "cover.png", "image/png", PNG);

        mvc.perform(multipart("/api/products/{id}/images", productId).file(image))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/products/{id}/images", productId).file(image)
                        .header("Authorization", "Bearer " + jwtService.issue(other.id()).accessToken()))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/products/{id}/images", productId).file(image)
                        .header("Authorization", "Bearer " + jwtService.issue(seller.id()).accessToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$[0].contentType").value("image/png"));
    }

    private UserResponse user(String nickname) {
        String email = UUID.randomUUID() + "@example.invalid";
        verificationService.send(email);
        UserResponse user = registrationService.register(new RegisterRequest(email, "password123", nickname,
                localMail.latestCodeForTest(email)));
        userIds.add(user.id());
        return user;
    }

    private List<ProductImageResponse> upload(long productId, long sellerId, int count) {
        List<MockMultipartFile> files = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            files.add(new MockMultipartFile("files", "image-" + i + ".png", "image/png", PNG));
        }
        return imageService.upload(productId, sellerId, new ArrayList<>(files));
    }

    private void assertOrders(long productId, List<ProductImageResponse> images, List<Integer> expected) {
        assertEquals(expected, images.stream().map(ProductImageResponse::sortOrder).toList());
        assertEquals(images.size(), jdbc.queryForObject(
                "SELECT COUNT(DISTINCT sort_order) FROM product_image WHERE product_id = ?",
                Integer.class, productId));
    }

    private long product(long sellerId) {
        long id = productService.create(new CreateProductRequest(1L, "图片测试商品", "用于图片集成测试",
                new BigDecimal("39.90"), ConditionLevel.LIKE_NEW), sellerId);
        productIds.add(id);
        return id;
    }
}
