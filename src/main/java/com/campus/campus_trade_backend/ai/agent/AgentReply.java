package com.campus.campus_trade_backend.ai.agent;

import com.campus.campus_trade_backend.ai.dto.ProductDraftSuggestion;
import java.util.List;

public record AgentReply(String sessionId, String reply,
                         List<AgentToolExecutor.ProductHit> products,
                         List<AgentToolExecutor.OrderHit> orders,
                         ProductDraftSuggestion draft) { }
