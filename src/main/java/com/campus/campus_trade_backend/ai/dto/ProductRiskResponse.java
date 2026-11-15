package com.campus.campus_trade_backend.ai.dto;

import java.util.List;

public record ProductRiskResponse(
        String riskLevel,
        List<String> riskTypes,
        List<String> reasons,
        List<String> suggestions,
        boolean needsManualReview,
        String analysisMode,
        String notice
) { }
