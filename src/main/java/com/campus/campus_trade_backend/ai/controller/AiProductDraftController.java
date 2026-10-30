package com.campus.campus_trade_backend.ai.controller;

import com.campus.campus_trade_backend.ai.dto.ProductDraftRequest;
import com.campus.campus_trade_backend.ai.dto.ProductDraftSuggestion;
import com.campus.campus_trade_backend.ai.service.ProductDraftService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiProductDraftController {
    private final ProductDraftService productDraftService;

    public AiProductDraftController(ProductDraftService productDraftService) {
        this.productDraftService = productDraftService;
    }

    @PostMapping("/product-draft")
    public ProductDraftSuggestion suggest(@AuthenticationPrincipal Jwt jwt,
                                          @Valid @RequestBody ProductDraftRequest request) {
        return productDraftService.suggest(Long.parseLong(jwt.getSubject()), request);
    }
}
