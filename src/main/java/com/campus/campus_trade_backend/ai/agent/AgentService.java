package com.campus.campus_trade_backend.ai.agent;

import com.campus.campus_trade_backend.ai.client.OpenAiResponsesGateway;
import com.campus.campus_trade_backend.ai.config.AiPrompts;
import com.campus.campus_trade_backend.ai.config.AiProperties;
import com.campus.campus_trade_backend.ai.dto.ProductDraftSuggestion;
import com.campus.campus_trade_backend.ai.service.AiRequestRateLimiter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentService {
    private static final int MAX_TOOL_CALLS = 4;
    private static final int MAX_ROUNDS = 5;
    private static final Duration MAX_TIME = Duration.ofSeconds(45);
    private final AiProperties properties;
    private final AiPrompts prompts;
    private final OpenAiResponsesGateway gateway;
    private final AgentToolExecutor tools;
    private final AgentConversationStore sessions;
    private final AiRequestRateLimiter limiter;
    private final ObjectMapper mapper;

    public AgentService(AiProperties properties, AiPrompts prompts, OpenAiResponsesGateway gateway,
                        AgentToolExecutor tools, AgentConversationStore sessions,
                        AiRequestRateLimiter limiter, ObjectMapper mapper) {
        this.properties = properties;
        this.prompts = prompts;
        this.gateway = gateway;
        this.tools = tools;
        this.sessions = sessions;
        this.limiter = limiter;
        this.mapper = mapper;
    }

    public AgentReply chat(long userId, AgentRequest request) {
        if (!properties.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 助手尚未启用；商品搜索和手动发布仍可正常使用");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "AI 助手尚未配置模型密钥；商品搜索和手动发布仍可正常使用");
        }
        gateway.ensureConfigured();
        limiter.check(userId);
        AgentConversationStore.Session session = sessions.open(userId, request.sessionId());
        List<Object> input = new ArrayList<>();
        input.add(Map.of("role", "system", "content", prompts.agent()));
        for (AgentConversationStore.Turn turn : session.turns()) {
            input.add(Map.of("role", "user", "content", turn.question()));
            input.add(Map.of("role", "assistant", "content", turn.answer()));
        }
        input.add(Map.of("role", "user", "content", request.message().trim()));
        Map<Long, AgentToolExecutor.ProductHit> products = new LinkedHashMap<>();
        Map<Long, AgentToolExecutor.OrderHit> orders = new LinkedHashMap<>();
        ProductDraftSuggestion draft = null;
        int toolCalls = 0;
        Instant deadline = Instant.now().plus(MAX_TIME);

        for (int round = 0; round < MAX_ROUNDS; round++) {
            if (Instant.now().isAfter(deadline)) throw unavailable("AI 助手处理超时，请稍后重试");
            JsonNode response;
            try { response = mapper.readTree(gateway.complete(body(input))); }
            catch (JacksonException exception) { throw unavailable("AI 助手返回格式不正确，请稍后重试"); }
            if (!"completed".equals(response.path("status").asString()) || !response.path("output").isArray()) {
                throw unavailable("AI 助手回复不完整，请稍后重试");
            }
            List<JsonNode> calls = new ArrayList<>();
            String answer = null;
            for (JsonNode item : response.path("output")) {
                if ("function_call".equals(item.path("type").asString())) calls.add(item);
                for (JsonNode content : item.path("content")) {
                    if ("output_text".equals(content.path("type").asString())) answer = content.path("text").asString();
                }
            }
            if (calls.isEmpty()) {
                if (answer == null || answer.isBlank() || answer.length() > 3000) {
                    throw unavailable("AI 助手没有返回有效回复，请稍后重试");
                }
                sessions.save(userId, session, request.message().trim(), answer);
                return new AgentReply(session.id(), answer, List.copyOf(products.values()),
                        List.copyOf(orders.values()), draft);
            }
            if (toolCalls + calls.size() > MAX_TOOL_CALLS || round == MAX_ROUNDS - 1) {
                throw unavailable("AI 助手调用工具次数过多，请缩小问题范围");
            }
            // store=false: replay model output items and append results with the matching call_id.
            for (JsonNode item : response.path("output")) input.add(item);
            for (JsonNode call : calls) {
                toolCalls++;
                String name = call.path("name").asString();
                String callId = call.path("call_id").asString();
                String arguments = call.path("arguments").asString();
                if (callId.isBlank() || callId.length() > 120 || arguments.length() > 3000) {
                    throw unavailable("AI 工具请求格式不正确");
                }
                if (!List.of("searchProducts", "getProductDetail", "getMyOrders", "generateProductDraft").contains(name)) {
                    throw unavailable("AI 请求了未授权的工具");
                }
                Object result;
                try {
                    AgentToolExecutor.ToolResult executed = tools.execute(name, mapper.readTree(arguments), userId);
                    executed.products().forEach(hit -> products.put(hit.id(), hit));
                    executed.orders().forEach(hit -> orders.put(hit.id(), hit));
                    if (executed.draft() != null) draft = executed.draft();
                    result = executed.data();
                } catch (JacksonException | ResponseStatusException exception) {
                    result = Map.of("error", "工具参数无效或结果不可用；请更正参数或向用户说明");
                }
                String output = mapper.writeValueAsString(result);
                if (output.length() > 12000) output = "{\"error\":\"结果过大，请缩小查询范围\"}";
                input.add(Map.of("type", "function_call_output", "call_id", callId, "output", output));
            }
        }
        throw unavailable("AI 助手未能完成请求，请稍后重试");
    }

    private String body(List<Object> input) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", properties.getModel().trim());
        request.put("input", input);
        request.put("tools", toolDefinitions());
        request.put("tool_choice", "auto");
        request.put("parallel_tool_calls", false);
        request.put("max_output_tokens", 700);
        request.put("store", false);
        return mapper.writeValueAsString(request);
    }

    private static List<Map<String, Object>> toolDefinitions() {
        return List.of(
                tool("searchProducts", "搜索真实在售商品；无条件时传 null", Map.of(
                        "query", nullable("string"), "categoryId", nullable("integer"),
                        "minPrice", nullable("number"), "maxPrice", nullable("number"))),
                tool("getProductDetail", "查询真实在售商品详情", Map.of("productId", Map.of("type", "integer"))),
                tool("getMyOrders", "只查询当前登录用户买到的商品订单；不接受用户 ID", Map.of()),
                tool("generateProductDraft", "根据用户提供的真实资料生成可编辑草稿，不发布商品", Map.of(
                        "rawTitle", Map.of("type", "string"), "rawDescription", Map.of("type", "string"),
                        "conditionLevel", Map.of("type", "string", "enum", List.of("NEW", "LIKE_NEW", "GOOD", "FAIR")))));
    }

    private static Map<String, Object> nullable(String type) {
        return Map.of("type", List.of(type, "null"));
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> fields) {
        return Map.of("type", "function", "name", name, "description", description, "strict", true,
                "parameters", Map.of("type", "object", "properties", fields,
                        "required", List.copyOf(fields.keySet()), "additionalProperties", false));
    }

    private static ResponseStatusException unavailable(String message) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
    }
}
