package com.campus.campus_trade_backend.ai.agent;

import com.campus.campus_trade_backend.ai.dto.ProductDraftRequest;
import com.campus.campus_trade_backend.ai.service.ProductDraftService;
import com.campus.campus_trade_backend.order.dto.OrderResponse;
import com.campus.campus_trade_backend.order.service.OrderService;
import com.campus.campus_trade_backend.product.dto.ConditionLevel;
import com.campus.campus_trade_backend.product.dto.ProductDetailResponse;
import com.campus.campus_trade_backend.product.dto.ProductSummaryResponse;
import com.campus.campus_trade_backend.product.service.ProductService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tool results are deliberately small and contain only data verified by business services. */
@Component
public class AgentToolExecutor {
    private final ProductService products;
    private final OrderService orders;
    private final ProductDraftService drafts;

    public AgentToolExecutor(ProductService products, OrderService orders, ProductDraftService drafts) {
        this.products = products;
        this.orders = orders;
        this.drafts = drafts;
    }

    public ToolResult execute(String name, JsonNode args, long userId) {
        if (!args.isObject()) throw badArguments();
        return switch (name) {
            case "searchProducts" -> search(args);
            case "getProductDetail" -> detail(args);
            case "getMyOrders" -> myOrders(args, userId);
            case "generateProductDraft" -> draft(args, userId);
            default -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 请求了未授权的工具");
        };
    }

    private ToolResult search(JsonNode args) {
        only(args, "query", "categoryId", "minPrice", "maxPrice");
        String query = optionalText(args, "query", 100);
        Long categoryId = optionalLong(args, "categoryId");
        BigDecimal min = optionalPrice(args, "minPrice");
        BigDecimal max = optionalPrice(args, "maxPrice");
        var page = products.list(1, 6, query, categoryId, min, max, "PRICE_ASC");
        List<ProductHit> hits = page.items().stream().map(AgentToolExecutor::hit).toList();
        return new ToolResult(Map.of("products", hits, "total", page.total()), hits, List.of(), null);
    }

    private ToolResult detail(JsonNode args) {
        only(args, "productId");
        Long id = requiredLong(args, "productId");
        ProductDetailResponse product = products.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在或不在售"));
        ProductHit hit = new ProductHit(product.id(), product.title(), product.price(),
                product.categoryName(), product.condition(), product.images().isEmpty() ? null : product.images().get(0).url());
        return new ToolResult(Map.of("product", Map.of("id", product.id(), "title", product.title(),
                "price", product.price(), "category", product.categoryName(), "condition", product.condition(),
                "description", product.description().substring(0, Math.min(1000, product.description().length())))),
                List.of(hit), List.of(), null);
    }

    private ToolResult myOrders(JsonNode args, long userId) {
        only(args);
        List<OrderHit> hits = new ArrayList<>();
        for (OrderResponse order : orders.listPurchases(userId, 1, 6, null).items()) {
            hits.add(new OrderHit(order.id(), order.productId(), order.productTitle(), order.status()));
        }
        return new ToolResult(Map.of("orders", hits), List.of(), hits, null);
    }

    private ToolResult draft(JsonNode args, long userId) {
        only(args, "rawTitle", "rawDescription", "conditionLevel");
        String title = requiredText(args, "rawTitle", 120);
        String description = requiredText(args, "rawDescription", 2000);
        ConditionLevel condition;
        try { condition = ConditionLevel.valueOf(requiredText(args, "conditionLevel", 20)); }
        catch (IllegalArgumentException exception) { throw badArguments(); }
        var suggestion = drafts.suggest(userId, new ProductDraftRequest(title, description, condition));
        return new ToolResult(Map.of("draft", suggestion), List.of(), List.of(), suggestion);
    }

    private static ProductHit hit(ProductSummaryResponse product) {
        return new ProductHit(product.id(), product.title(), product.price(), product.categoryName(),
                product.condition(), product.coverImageUrl());
    }

    private static void only(JsonNode args, String... allowed) {
        Set<String> names = Set.of(allowed);
        for (var field : args.properties()) if (!names.contains(field.getKey())) throw badArguments();
    }

    private static String optionalText(JsonNode args, String key, int limit) {
        JsonNode node = args.path(key);
        if (node.isMissingNode() || node.isNull()) return "";
        if (!node.isTextual() || node.asString().length() > limit) throw badArguments();
        return node.asString().trim();
    }

    private static String requiredText(JsonNode args, String key, int limit) {
        String value = optionalText(args, key, limit);
        if (value.isBlank()) throw badArguments();
        return value;
    }

    private static Long optionalLong(JsonNode args, String key) {
        JsonNode value = args.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isIntegralNumber() || value.asLong() < 1) throw badArguments();
        return value.asLong();
    }

    private static Long requiredLong(JsonNode args, String key) {
        Long result = optionalLong(args, key);
        if (result == null) throw badArguments();
        return result;
    }

    private static BigDecimal optionalPrice(JsonNode args, String key) {
        JsonNode value = args.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isNumber()) throw badArguments();
        BigDecimal price = value.decimalValue();
        if (price.signum() < 0 || price.compareTo(new BigDecimal("99999999")) > 0 || price.scale() > 2)
            throw badArguments();
        return price;
    }

    private static ResponseStatusException badArguments() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 工具参数不合法，请换种说法重试");
    }

    public record ProductHit(Long id, String title, BigDecimal price, String category,
                             String condition, String imageUrl) { }
    public record OrderHit(long id, long productId, String productTitle, String status) { }
    public record ToolResult(Object data, List<ProductHit> products, List<OrderHit> orders,
                             com.campus.campus_trade_backend.ai.dto.ProductDraftSuggestion draft) { }
}
