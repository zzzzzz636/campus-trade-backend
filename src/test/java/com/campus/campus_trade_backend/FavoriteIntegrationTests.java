package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.favorite.service.FavoriteService;
import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:campustrade_favorites;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
class FavoriteIntegrationTests {
    @Autowired FavoriteService favoriteService;
    @Autowired ProductService productService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void favoriteIsIdempotentIsolatedAndKeepsOffShelfProductVisible() {
        long seller = user("收藏商品卖家");
        long firstUser = user("收藏用户一");
        long secondUser = user("收藏用户二");
        long product = productService.create(new CreateProductRequest(1L, "值得收藏的教材", "测试收藏持久化",
                new BigDecimal("19.90"), ConditionLevel.GOOD), seller);

        favoriteService.add(firstUser, product);
        favoriteService.add(firstUser, product);
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_favorite WHERE user_id = ? AND product_id = ?",
                Long.class, firstUser, product));
        assertEquals(1, favoriteService.productIds(firstUser).size());
        assertTrue(favoriteService.productIds(secondUser).isEmpty());

        // 收藏记录不等于公开展示权限；下架后仍在本人的收藏列表中并明确显示状态。
        productService.takeDown(product, seller);
        var favorites = favoriteService.list(firstUser, 1, 20);
        assertEquals(1, favorites.size());
        assertEquals("OFF_SHELF", favorites.get(0).status());
        assertEquals("值得收藏的教材", favorites.get(0).title());

        favoriteService.remove(secondUser, product);
        assertEquals(1, favoriteService.list(firstUser, 1, 20).size());
        favoriteService.remove(firstUser, product);
        favoriteService.remove(firstUser, product);
        assertTrue(favoriteService.list(firstUser, 1, 20).isEmpty());
    }

    private long user(String nickname) {
        String email = UUID.randomUUID() + "@example.invalid";
        jdbc.update("""
                INSERT INTO app_user (email, password_hash, nickname, status, created_at)
                VALUES (?, 'test-only', ?, 'ACTIVE', CURRENT_TIMESTAMP)
                """, email, nickname);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email = ?", Long.class, email);
    }
}
