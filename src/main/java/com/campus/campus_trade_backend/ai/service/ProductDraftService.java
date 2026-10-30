package com.campus.campus_trade_backend.ai.service;

import com.campus.campus_trade_backend.ai.client.ProductDraftAiClient;
import com.campus.campus_trade_backend.ai.dto.ProductDraftRequest;
import com.campus.campus_trade_backend.ai.dto.ProductDraftSuggestion;
import org.springframework.stereotype.Service;

@Service
public class ProductDraftService {
    private final ProductDraftAiClient aiClient;
    private final AiRequestRateLimiter rateLimiter;

    public ProductDraftService(ProductDraftAiClient aiClient, AiRequestRateLimiter rateLimiter) {
        this.aiClient = aiClient;
        this.rateLimiter = rateLimiter;
    }

    public ProductDraftSuggestion suggest(long userId, ProductDraftRequest request) {
        rateLimiter.check(userId);
        return aiClient.generate(request);
    }
}
