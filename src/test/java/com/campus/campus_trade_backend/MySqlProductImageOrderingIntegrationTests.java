package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.service.ProductImageService;
import com.campus.campus_trade_backend.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "campus.image.mysql.url", matches = "jdbc:mysql:.*")
@ActiveProfiles("mysql")
@SpringBootTest(properties = {
        "CAMPUS_DB_PASSWORD=integration-test-only",
        "CAMPUS_JWT_SECRET=QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=",
        "spring.flyway.baseline-on-migrate=false",
        "spring.data.redis.url=redis://127.0.0.1:6379",
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=1025",
        "spring.mail.properties.mail.smtp.auth=false",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "campus.mail.from=noreply@campustrade.local",
        "campus.storage.upload-dir=${java.io.tmpdir}/campus-trade-image-mysql-tests"
})
class MySqlProductImageOrderingIntegrationTests {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @DynamicPropertySource
    static void isolatedMySql(DynamicPropertyRegistry registry) {
        String url = System.getProperty("campus.image.mysql.url");
        if (!url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):\\d+/campus_image_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("图片并发测试只能连接本机的 campus_image_test 独立数据库");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getProperty("campus.image.mysql.user", "campus_image"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("CAMPUS_IMAGE_MYSQL_PASSWORD", ""));
    }

    @Autowired ProductImageService imageService;
    @Autowired ProductService productService;
    @Autowired JdbcTemplate jdbc;

    @Test
    @Timeout(45)
    void simultaneousUploadsForOneProductKeepUniqueSortOrders() throws Exception {
        long sellerId = user();
        long productId = product(sellerId);
        var original = imageService.upload(productId, sellerId, List.of(image(), image(), image()));
        imageService.delete(productId, original.get(1).id(), sellerId);
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> uploads = new ArrayList<>();
            for (int request = 0; request < 2; request++) {
                uploads.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    imageService.upload(productId, sellerId, List.of(image(), image()));
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> upload : uploads) upload.get(30, TimeUnit.SECONDS);

            assertEquals(List.of(0, 2, 3, 4, 5, 6), jdbc.queryForList(
                    "SELECT sort_order FROM product_image WHERE product_id = ? ORDER BY sort_order", Integer.class, productId));
            assertEquals(6, jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT sort_order) FROM product_image WHERE product_id = ?", Integer.class, productId));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private MockMultipartFile image() {
        return new MockMultipartFile("files", "concurrent.png", "image/png", PNG);
    }

    private long user() {
        String email = UUID.randomUUID() + "@example.invalid";
        jdbc.update("""
                INSERT INTO app_user (email, password_hash, nickname, status, created_at)
                VALUES (?, 'integration-test-only', '图片排序卖家', 'ACTIVE', CURRENT_TIMESTAMP)
                """, email);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    }

    private long product(long sellerId) {
        return productService.create(new CreateProductRequest(1L, "并发图片商品", "验证图片排序锁",
                new BigDecimal("39.00"), ConditionLevel.GOOD), sellerId);
    }
}
