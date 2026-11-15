package com.campus.campus_trade_backend.ai.controller;

import com.campus.campus_trade_backend.ai.dto.ProductRiskRequest;
import com.campus.campus_trade_backend.ai.dto.ProductRiskResponse;
import com.campus.campus_trade_backend.ai.service.ProductRiskService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiProductRiskController {
    private final ProductRiskService service;

    public AiProductRiskController(ProductRiskService service) {
        this.service = service;
    }

    @PostMapping("/product-risk")
    public ProductRiskResponse analyze(@AuthenticationPrincipal Jwt jwt,
                                       @Valid @RequestBody ProductRiskRequest request) {
        return service.analyze(Long.parseLong(jwt.getSubject()), request);
    }
}
