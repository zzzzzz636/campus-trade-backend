package com.campus.campus_trade_backend;

import com.campus.campus_trade_backend.auth.dto.LoginRequest;
import com.campus.campus_trade_backend.auth.dto.RegisterRequest;
import com.campus.campus_trade_backend.auth.service.AuthService;
import com.campus.campus_trade_backend.auth.service.RegistrationService;
import com.campus.campus_trade_backend.auth.verification.LocalConsoleMailSender;
import com.campus.campus_trade_backend.auth.verification.VerificationService;
import com.campus.campus_trade_backend.order.service.OrderService;
import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:campustrade_productv3;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@Transactional
class ProductV3IntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ProductService productService;
    @Autowired OrderService orderService;
    @Autowired VerificationService verificationService;
    @Autowired RegistrationService registrationService;
    @Autowired LocalConsoleMailSender localMail;
    @Autowired AuthService authService;

    @Test
    void filtersSortsAndReturnsCategoryAndTimeMetadata() throws Exception {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        long expensive = productService.create(product(1L, marker + " 教材", "30.00"), 1L);
        long cheapest = productService.create(product(2L, marker + " 耳机", "10.00"), 1L);
        long middle = productService.create(product(2L, marker + " 台灯", "20.00"), 1L);

        var ascending = productService.list(1, 10, marker, null, null, null, "PRICE_ASC");
        assertEquals(3, ascending.total());
        assertEquals(cheapest, ascending.items().get(0).id());
        assertEquals(middle, ascending.items().get(1).id());
        assertEquals(expensive, ascending.items().get(2).id());
        assertNotNull(ascending.items().get(0).createdAt());

        mvc.perform(get("/api/products")
                        .param("q", marker)
                        .param("categoryId", "2")
                        .param("minPrice", "15.00")
                        .param("maxPrice", "25.00")
                        .param("sort", "PRICE_DESC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(middle))
                .andExpect(jsonPath("$.items[0].categoryName").value("数码与电器"))
                .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty());
    }

    @Test
    void onlySellerCanEditAnOnSaleProduct() throws Exception {
        Session seller = register("V3 卖家");
        Session buyer = register("V3 买家");
        long productId = productService.create(product(1L, "待编辑商品", "26.00"), seller.id());
        mvc.perform(get("/api/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0));
        String body = """
                {"version":0,"categoryId":2,"title":"更新后的台灯","description":"描述已经更新", "price":19.90,"conditionLevel":"LIKE_NEW"}
                """;

        mvc.perform(put("/api/products/{id}", productId)
                        .header("Authorization", "Bearer " + seller.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("\"version\":0,", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        mvc.perform(put("/api/products/{id}", productId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/products/{id}", productId)
                        .header("Authorization", "Bearer " + buyer.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("只能编辑自己的商品"));
        mvc.perform(put("/api/products/{id}", productId)
                        .header("Authorization", "Bearer " + seller.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("更新后的台灯"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.categoryName").value("数码与电器"))
                .andExpect(jsonPath("$.price").value(19.90))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        mvc.perform(put("/api/products/{id}", productId)
                        .header("Authorization", "Bearer " + seller.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("商品信息或状态已变化，请刷新后重试"));

        orderService.create(productId, buyer.id(), UUID.randomUUID().toString());
        mvc.perform(put("/api/products/{id}", productId)
                        .header("Authorization", "Bearer " + seller.token())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("只有在售商品可以编辑"));
    }

    @Test
    void rejectsInvalidProductQueryParameters() throws Exception {
        mvc.perform(get("/api/products").param("minPrice", "20").param("maxPrice", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("最低价格不能高于最高价格"));
        mvc.perform(get("/api/products").param("sort", "DROP_TABLE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("sort 仅支持 NEWEST、PRICE_ASC、PRICE_DESC"));
        mvc.perform(get("/api/products").param("minPrice", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("请求参数格式不正确：minPrice"));
    }

    private CreateProductRequest product(long categoryId, String title, String price) {
        return new CreateProductRequest(categoryId, title, "V3 商品筛选与编辑测试",
                new BigDecimal(price), ConditionLevel.GOOD);
    }

    private Session register(String nickname) {
        String email = UUID.randomUUID() + "@example.invalid";
        verificationService.send(email);
        var user = registrationService.register(new RegisterRequest(
                email, "password123", nickname, localMail.latestCodeForTest(email)));
        String token = authService.login(new LoginRequest(email, "password123")).accessToken();
        return new Session(user.id(), token);
    }

    private record Session(long id, String token) { }
}
