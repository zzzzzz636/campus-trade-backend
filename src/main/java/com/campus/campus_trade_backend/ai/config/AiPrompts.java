package com.campus.campus_trade_backend.ai.config;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class AiPrompts {
    private final String productDraft = read("prompts/product-draft-v1.txt");
    private final String productRisk = read("prompts/product-risk-v1.txt");
    private final String agent = read("prompts/agent-v1.txt");

    public String productDraft() { return productDraft; }
    public String productRisk() { return productRisk; }
    public String agent() { return agent; }

    private static String read(String path) {
        try {
            String content = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8).trim();
            if (content.isBlank()) throw new IllegalStateException("AI 提示词为空：" + path);
            return content;
        } catch (IOException exception) {
            throw new IllegalStateException("无法加载 AI 提示词：" + path, exception);
        }
    }
}
