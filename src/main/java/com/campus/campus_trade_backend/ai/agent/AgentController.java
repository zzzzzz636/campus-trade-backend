package com.campus.campus_trade_backend.ai.agent;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai/agent")
public class AgentController {
    private final AgentService agent;

    public AgentController(AgentService agent) { this.agent = agent; }

    @PostMapping("/chat")
    public AgentReply chat(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AgentRequest request) {
        return agent.chat(Long.parseLong(jwt.getSubject()), request);
    }
}
