package com.campus.campus_trade_backend.ai.dto;

import java.util.List;

public record ProductRiskSignal(
        String riskLevel,
        List<String> riskTypes,
        List<String> reasons,
        List<String> suggestions,
        boolean needsManualReview
) { }
