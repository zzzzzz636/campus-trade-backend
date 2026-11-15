package com.campus.campus_trade_backend.ai.service;

import com.campus.campus_trade_backend.ai.client.OpenAiProductRiskClient;
import com.campus.campus_trade_backend.ai.dto.ProductRiskRequest;
import com.campus.campus_trade_backend.ai.dto.ProductRiskSignal;
import com.campus.campus_trade_backend.category.entity.CategoryEntity;
import com.campus.campus_trade_backend.category.mapper.CategoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductRiskServiceTests {
    private final CategoryMapper categories = mock(CategoryMapper.class);
    private final OpenAiProductRiskClient model = mock(OpenAiProductRiskClient.class);
    private final AiRequestRateLimiter limiter = mock(AiRequestRateLimiter.class);
    private ProductRiskService service;

    @BeforeEach
    void setUp() {
        CategoryEntity category = new CategoryEntity();
        category.setId(1L);
        category.setName("教材与书籍");
        when(categories.list()).thenReturn(List.of(category));
        when(model.configured()).thenReturn(true);
        service = new ProductRiskService(categories, model, limiter);
    }

    @ParameterizedTest
    @EnumSource(value = HttpStatus.class, names = {"BAD_GATEWAY", "GATEWAY_TIMEOUT", "SERVICE_UNAVAILABLE"})
    void providerFailureOrMalformedOutputDegradesToRules(HttpStatus status) {
        when(model.analyze(any(), eq("教材与书籍")))
                .thenThrow(new ResponseStatusException(status, "模拟模型故障"));
        var response = service.analyze(1L, request());
        assertEquals("RULES_ONLY", response.analysisMode());
        assertEquals("low", response.riskLevel());
    }

    @Test
    void unavailableRedisLimiterDoesNotClaimModelSucceeded() {
        doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "模拟 Redis 不可用"))
                .when(limiter).check(1L);
        var response = service.analyze(1L, request());
        assertEquals("RULES_ONLY", response.analysisMode());
    }

    @Test
    void modelCannotClassifyPriceAloneAsHighRiskOrMandatoryReview() {
        when(model.analyze(any(), eq("教材与书籍"))).thenReturn(new ProductRiskSignal("high",
                List.of("SUSPICIOUS_PRICE"), List.of("价格低"), List.of("核对原价"), true));
        var response = service.analyze(1L, request());
        assertEquals("medium", response.riskLevel());
        assertFalse(response.needsManualReview());
        assertEquals("RULES_AND_AI", response.analysisMode());
    }

    private ProductRiskRequest request() {
        return new ProductRiskRequest("旧教材", "书页完整，东门面交，毕业清理",
                new BigDecimal("1.00"), 1L);
    }
}
