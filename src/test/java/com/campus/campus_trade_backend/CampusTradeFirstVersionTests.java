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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:campustrade_firstversion;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
class CampusTradeFirstVersionTests {
    @Autowired AuthService authService;
    @Autowired RegistrationService registrationService;
    @Autowired VerificationService verificationService;
    @Autowired LocalConsoleMailSender localMail;
    @Autowired ProductService productService;
    @Autowired OrderService orderService;
    @Autowired JwtDecoder jwtDecoder;

    @Test
    @Transactional
    void registerLoginPublishSelectAndTakeDown() {
        String suffix = UUID.randomUUID().toString();
        String sellerEmail = "seller-" + suffix + "@example.invalid";
        var seller = register(sellerEmail, "password123", "卖家");
        var buyer = register("buyer-" + suffix + "@example.invalid", "password456", "买家");
        var token = authService.login(new LoginRequest(sellerEmail, "password123"));
        assertEquals(Long.toString(seller.id()), jwtDecoder.decode(token.accessToken()).getSubject());

        String searchableTitle = "二手教材" + suffix.substring(0, 8);
        long productId = productService.create(new CreateProductRequest(1L, searchableTitle, "校内面交",
                new BigDecimal("20.00"), ConditionLevel.GOOD), seller.id());
        assertTrue(productService.findById(productId).isPresent());
        assertEquals(1, productService.list(1, 10, suffix.substring(0, 8)).total());
        assertEquals("卖家", productService.list(1, 10, suffix.substring(0, 8)).items().get(0).sellerNickname());
        assertEquals(productId, productService.listMine(seller.id()).get(0).id());
        assertTrue(productService.listMine(buyer.id()).isEmpty());
        assertThrows(ResponseStatusException.class, () -> productService.takeDown(productId, buyer.id()));

        var order = orderService.create(productId, buyer.id());
        assertEquals("PENDING_MEETUP", order.status());
        assertEquals(searchableTitle, orderService.listPurchases(buyer.id()).get(0).productTitle());
        assertTrue(orderService.listPurchases(seller.id()).isEmpty());
        assertEquals("RESERVED", productService.listMine(seller.id()).get(0).status());
        assertFalse(productService.findById(productId).isPresent());
        assertThrows(ResponseStatusException.class, () -> orderService.create(productId, buyer.id()));
        assertThrows(ResponseStatusException.class, () -> productService.takeDown(productId, seller.id()));

        long anotherId = productService.create(new CreateProductRequest(1L, "旧书", "可以自提",
                new BigDecimal("10.00"), ConditionLevel.FAIR), seller.id());
        productService.takeDown(anotherId, seller.id());
        assertFalse(productService.findById(anotherId).isPresent());
        assertEquals("OFF_SHELF", productService.listMine(seller.id()).get(0).status());
    }

    private com.campus.campus_trade_backend.auth.dto.UserResponse register(String email, String password, String nickname) {
        verificationService.send(email);
        return registrationService.register(new RegisterRequest(email, password, nickname, localMail.latestCodeForTest(email)));
    }
}
