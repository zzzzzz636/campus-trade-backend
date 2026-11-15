package com.campus.campus_trade_backend.ai.client;

import com.campus.campus_trade_backend.ai.config.AiProperties;
import com.campus.campus_trade_backend.ai.config.AiPrompts;
import com.campus.campus_trade_backend.ai.dto.ProductDraftRequest;
import com.campus.campus_trade_backend.ai.dto.ProductDraftSuggestion;
import com.campus.campus_trade_backend.category.entity.CategoryEntity;
import com.campus.campus_trade_backend.category.mapper.CategoryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class OpenAiProductDraftClient implements ProductDraftAiClient {
    private final AiProperties properties;
    private final AiPrompts prompts;
    private final ObjectMapper objectMapper;
    private final CategoryMapper categoryMapper;
    private final OpenAiResponsesGateway gateway;

    @Autowired
    public OpenAiProductDraftClient(AiProperties properties, ObjectMapper objectMapper,
                                    CategoryMapper categoryMapper, AiPrompts prompts,
                                    OpenAiResponsesGateway gateway) {
        this.properties = properties;
        this.prompts = prompts;
        this.objectMapper = objectMapper;
        this.categoryMapper = categoryMapper;
        this.gateway = gateway;
    }

    OpenAiProductDraftClient(AiProperties properties, ObjectMapper objectMapper,
                             CategoryMapper categoryMapper, HttpClient httpClient) {
        this(properties, objectMapper, categoryMapper, new AiPrompts(),
                new OpenAiResponsesGateway(properties, httpClient));
    }

    @Override
    public ProductDraftSuggestion generate(ProductDraftRequest request) {
        gateway.ensureConfigured();
        List<CategoryEntity> categories = categoryMapper.list();
        if (categories.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "商品分类尚未配置，暂时无法使用 AI 辅助");
        }

        return parseSuggestion(gateway.complete(buildRequestBody(request, categories)), categories);
    }

    private String buildRequestBody(ProductDraftRequest request, List<CategoryEntity> categories) {
        List<Long> categoryIds = categories.stream().map(CategoryEntity::getId).toList();
        List<Map<String, Object>> allowedCategories = categories.stream()
                .map(category -> Map.<String, Object>of("id", category.getId(), "name", category.getName()))
                .toList();

        Map<String, Object> propertiesSchema = new LinkedHashMap<>();
        propertiesSchema.put("title", Map.of("type", "string", "minLength", 1, "maxLength", 120));
        propertiesSchema.put("description", Map.of("type", "string", "minLength", 1, "maxLength", 5000));
        propertiesSchema.put("categoryId", Map.of("type", "integer", "enum", categoryIds));
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", propertiesSchema,
                "required", List.of("title", "description", "categoryId"),
                "additionalProperties", false
        );
        Map<String, Object> format = Map.of(
                "type", "json_schema",
                "name", "campus_trade_product_draft",
                "strict", true,
                "schema", schema
        );
        Map<String, Object> userInput = new LinkedHashMap<>();
        userInput.put("rawTitle", request.rawTitle() == null ? "" : request.rawTitle().trim());
        userInput.put("rawDescription", request.rawDescription().trim());
        userInput.put("conditionLevel", request.conditionLevel().name());
        userInput.put("allowedCategories", allowedCategories);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel().trim());
        body.put("input", List.of(
                Map.of("role", "system", "content", prompts.productDraft()),
                Map.of("role", "user", "content", objectMapper.writeValueAsString(userInput))
        ));
        body.put("text", Map.of("format", format));
        body.put("max_output_tokens", 800);
        body.put("store", false);
        return objectMapper.writeValueAsString(body);
    }

    private ProductDraftSuggestion parseSuggestion(String responseBody, List<CategoryEntity> categories) {
        try {
            JsonNode response = objectMapper.readTree(responseBody);
            if ("incomplete".equals(response.path("status").asString())) {
                throw invalidOutput("AI 输出不完整，请重试或继续手动填写");
            }
            String outputText = null;
            boolean refused = false;
            for (JsonNode output : response.path("output")) {
                for (JsonNode content : output.path("content")) {
                    if ("output_text".equals(content.path("type").asString())) {
                        outputText = content.path("text").asString();
                        break;
                    }
                    if ("refusal".equals(content.path("type").asString())) refused = true;
                }
                if (outputText != null) break;
            }
            if (outputText == null || outputText.isBlank()) {
                throw invalidOutput(refused ? "AI 无法处理这段商品信息，请手动填写" : "AI 未返回有效商品信息，请重试");
            }

            JsonNode draft = objectMapper.readTree(outputText);
            String title = draft.path("title").asString().trim();
            String description = draft.path("description").asString().trim();
            JsonNode categoryNode = draft.path("categoryId");
            if (title.isBlank() || title.length() > 120 || description.isBlank() || description.length() > 5000
                    || !categoryNode.isIntegralNumber()) {
                throw invalidOutput("AI 返回的商品信息格式不正确，请重试或手动填写");
            }

            long categoryId = categoryNode.asLong();
            Map<Long, CategoryEntity> categoryById = categories.stream()
                    .collect(Collectors.toMap(CategoryEntity::getId, Function.identity()));
            CategoryEntity category = categoryById.get(categoryId);
            if (category == null) {
                throw invalidOutput("AI 返回了不存在的商品分类，请重试或手动选择");
            }
            return new ProductDraftSuggestion(title, description, categoryId, category.getName());
        } catch (JacksonException exception) {
            throw invalidOutput("AI 返回的内容无法解析，请重试或手动填写", exception);
        }
    }

    private static ResponseStatusException invalidOutput(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }

    private static ResponseStatusException invalidOutput(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message, cause);
    }
}
