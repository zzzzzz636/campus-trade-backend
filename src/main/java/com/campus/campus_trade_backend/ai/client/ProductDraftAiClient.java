package com.campus.campus_trade_backend.ai.client;

import com.campus.campus_trade_backend.ai.dto.ProductDraftRequest;
import com.campus.campus_trade_backend.ai.dto.ProductDraftSuggestion;

public interface ProductDraftAiClient {
    ProductDraftSuggestion generate(ProductDraftRequest request);
}
