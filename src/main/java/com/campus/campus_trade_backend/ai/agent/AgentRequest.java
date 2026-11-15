package com.campus.campus_trade_backend.ai.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgentRequest(@NotBlank @Size(max = 500) String message,
                           @Size(max = 36) String sessionId) { }
