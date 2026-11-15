package com.campus.campus_trade_backend.ai.client;

import com.campus.campus_trade_backend.ai.config.AiPrompts;
import com.campus.campus_trade_backend.ai.config.AiProperties;
import com.campus.campus_trade_backend.ai.dto.ProductRiskRequest;
import com.campus.campus_trade_backend.ai.dto.ProductRiskSignal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class OpenAiProductRiskClient {
    private static final Set<String> LEVELS = Set.of("low", "medium", "high");
    private static final Set<String> TYPES = Set.of("SUSPICIOUS_PRICE", "ADVANCE_PAYMENT",
            "MISLEADING_CLAIM", "MISSING_DETAILS", "PROHIBITED_ITEM");

    private final AiProperties properties;
    private final AiPrompts prompts;
    private final OpenAiResponsesGateway gateway;
    private final ObjectMapper mapper;

    public OpenAiProductRiskClient(AiProperties properties, AiPrompts prompts,
                                   OpenAiResponsesGateway gateway, ObjectMapper mapper) {
        this.properties = properties;
        this.prompts = prompts;
        this.gateway = gateway;
        this.mapper = mapper;
    }

    public boolean configured() {
        return gateway.configured();
    }

    public ProductRiskSignal analyze(ProductRiskRequest request, String categoryName) {
        return parse(gateway.complete(buildRequest(request, categoryName)));
    }

    private String buildRequest(ProductRiskRequest request, String categoryName) {
        Map<String, Object> propertiesSchema = new LinkedHashMap<>();
        propertiesSchema.put("riskLevel", Map.of("type", "string", "enum", List.of("low", "medium", "high")));
        propertiesSchema.put("riskTypes", Map.of("type", "array", "items", Map.of("type", "string", "enum", TYPES),
                "maxItems", 5));
        propertiesSchema.put("reasons", Map.of("type", "array", "items", Map.of("type", "string", "maxLength", 180),
                "maxItems", 5));
        propertiesSchema.put("suggestions", Map.of("type", "array", "items", Map.of("type", "string", "maxLength", 180),
                "maxItems", 5));
        propertiesSchema.put("needsManualReview", Map.of("type", "boolean"));
        Map<String, Object> schema = Map.of(
                "type", "object", "properties", propertiesSchema,
                "required", List.of("riskLevel", "riskTypes", "reasons", "suggestions", "needsManualReview"),
                "additionalProperties", false);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("title", request.title().trim());
        input.put("description", request.description().trim());
        input.put("price", request.price());
        input.put("category", categoryName);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel().trim());
        body.put("input", List.of(
                Map.of("role", "system", "content", prompts.productRisk()),
                Map.of("role", "user", "content", mapper.writeValueAsString(input))));
        body.put("text", Map.of("format", Map.of("type", "json_schema", "name", "campus_trade_product_risk",
                "strict", true, "schema", schema)));
        body.put("max_output_tokens", 700);
        body.put("store", false);
        return mapper.writeValueAsString(body);
    }

    private ProductRiskSignal parse(String body) {
        try {
            JsonNode response = mapper.readTree(body);
            if (!"completed".equals(response.path("status").asString())) throw invalidOutput();
            String outputText = null;
            for (JsonNode item : response.path("output")) {
                for (JsonNode content : item.path("content")) {
                    if ("output_text".equals(content.path("type").asString())) {
                        outputText = content.path("text").asString();
                        break;
                    }
                }
                if (outputText != null) break;
            }
            if (outputText == null || outputText.isBlank()) throw invalidOutput();
            JsonNode value = mapper.readTree(outputText);
            String level = value.path("riskLevel").asString();
            List<String> types = strings(value.path("riskTypes"));
            List<String> reasons = strings(value.path("reasons"));
            List<String> suggestions = strings(value.path("suggestions"));
            if (!value.isObject() || value.size() != 5 || !LEVELS.contains(level)
                    || !TYPES.containsAll(types) || !value.path("needsManualReview").isBoolean()
                    || (!"low".equals(level) && (types.isEmpty() || reasons.isEmpty()))) {
                throw invalidOutput();
            }
            return new ProductRiskSignal(level, types, reasons, suggestions,
                    value.path("needsManualReview").asBoolean());
        } catch (JacksonException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 风险分析结果格式不正确", exception);
        }
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray() || node.size() > 5) throw invalidOutput();
        List<String> values = new java.util.ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) throw invalidOutput();
            String text = item.asString().trim();
            if (text.isBlank() || text.length() > 180) throw invalidOutput();
            values.add(text);
        }
        return List.copyOf(values);
    }

    private static ResponseStatusException invalidOutput() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 风险分析结果格式不正确");
    }
}
