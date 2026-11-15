package com.campus.campus_trade_backend.ai.service;

import com.campus.campus_trade_backend.ai.client.OpenAiProductRiskClient;
import com.campus.campus_trade_backend.ai.dto.ProductRiskRequest;
import com.campus.campus_trade_backend.ai.dto.ProductRiskResponse;
import com.campus.campus_trade_backend.ai.dto.ProductRiskSignal;
import com.campus.campus_trade_backend.category.entity.CategoryEntity;
import com.campus.campus_trade_backend.category.mapper.CategoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class ProductRiskService {
    private static final Logger log = LoggerFactory.getLogger(ProductRiskService.class);
    private static final Pattern ORIGINAL_PRICE = Pattern.compile("原价\\s*[：:]?\\s*[¥￥]?\\s*(\\d{2,8}(?:\\.\\d{1,2})?)");
    private static final Pattern PREPAYMENT = Pattern.compile("先付(?:款|钱)|提前付款|预付(?:款|定金)|先交(?:定金|订金)|付款后发货");
    private static final Pattern PRIVATE_TRANSFER = Pattern.compile("私下转账|微信转账|支付宝转账|加微信付款");
    private static final Pattern UNSUPPORTED_CLAIM = Pattern.compile("百分百正品|绝对正品|永久保修|保证升值|绝对全新");
    private static final Pattern PROHIBITED = Pattern.compile("电子烟|香烟|处方药|代写论文|作弊设备|枪支|管制刀具");
    private static final Pattern HANDOFF = Pattern.compile("面交|自提|邮寄|快递|交付|校内交易|交易地点");

    private final CategoryMapper categories;
    private final OpenAiProductRiskClient model;
    private final AiRequestRateLimiter rateLimiter;

    public ProductRiskService(CategoryMapper categories, OpenAiProductRiskClient model,
                              AiRequestRateLimiter rateLimiter) {
        this.categories = categories;
        this.model = model;
        this.rateLimiter = rateLimiter;
    }

    public ProductRiskResponse analyze(long userId, ProductRiskRequest request) {
        CategoryEntity category = categories.list().stream()
                .filter(row -> row.getId().equals(request.categoryId()))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "分类不存在"));
        ProductRiskSignal rules = rules(request);
        if (!model.configured()) return response(rules, "RULES_ONLY", "模型分析未启用，已完成规则检查；请自行核实商品信息。");
        try {
            rateLimiter.check(userId);
            ProductRiskSignal semantic = model.analyze(request, category.getName());
            return response(merge(rules, semantic), "RULES_AND_AI", "风险提示仅供核实，不代表违规事实。");
        } catch (ResponseStatusException exception) {
            int status = exception.getStatusCode().value();
            if (status != 429 && status != 502 && status != 503 && status != 504) throw exception;
            log.warn("AI risk analysis degraded, status={}", status);
            return response(rules, "RULES_ONLY", "模型分析暂不可用，已完成规则检查；请稍后重试模型分析。");
        }
    }

    private static ProductRiskSignal rules(ProductRiskRequest request) {
        String text = request.title() + "\n" + request.description();
        LinkedHashSet<String> types = new LinkedHashSet<>();
        List<String> reasons = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        String level = "low";
        boolean review = false;

        Matcher originalPrice = ORIGINAL_PRICE.matcher(text);
        if (originalPrice.find()) {
            BigDecimal quoted = new BigDecimal(originalPrice.group(1));
            if (quoted.compareTo(request.price().multiply(BigDecimal.TEN)) >= 0) {
                types.add("SUSPICIOUS_PRICE");
                reasons.add("标价明显低于卖家自述原价；原价与商品情况尚未核实。");
                suggestions.add("核对实物、购买凭证和标价原因，避免仅凭自述原价判断。");
                level = "medium";
            }
        }
        if (PREPAYMENT.matcher(text).find() || PRIVATE_TRANSFER.matcher(text).find()) {
            types.add("ADVANCE_PAYMENT");
            reasons.add("描述中出现提前付款或私下转账要求。");
            suggestions.add("交易前核实商品与对方身份，避免提前转账。");
            level = "high";
            review = true;
        }
        if (UNSUPPORTED_CLAIM.matcher(text).find()) {
            types.add("MISLEADING_CLAIM");
            reasons.add("描述包含难以直接验证的绝对化承诺。");
            suggestions.add("要求卖家提供实物照片、购买凭证和已知瑕疵说明。");
            if ("low".equals(level)) level = "medium";
        }
        if (PROHIBITED.matcher(text).find()) {
            types.add("PROHIBITED_ITEM");
            reasons.add("描述可能涉及不适合在校园平台交易的物品或服务。");
            suggestions.add("交易前核对校园规则及适用法律，必要时请人工复核。");
            level = "high";
            review = true;
        }
        if (!HANDOFF.matcher(text).find()) {
            types.add("MISSING_DETAILS");
            reasons.add("描述未说明面交、邮寄或其他交付方式。");
            suggestions.add("补充交付方式、地点及商品已知瑕疵，方便买家核实。");
        }
        if (reasons.isEmpty()) {
            reasons.add("未发现明确的交易风险线索；平台尚未核验商品真实性。");
            suggestions.add("线下面交时核验实物与商品描述。");
        }
        return new ProductRiskSignal(level, List.copyOf(types), List.copyOf(reasons),
                List.copyOf(suggestions), review);
    }

    private static ProductRiskSignal merge(ProductRiskSignal rules, ProductRiskSignal semantic) {
        // 没有其他证据的低价提示不得单独升级为“高风险”或自动人工审核。
        boolean priceOnly = semantic.riskTypes().stream().allMatch("SUSPICIOUS_PRICE"::equals);
        String modelLevel = priceOnly && "high".equals(semantic.riskLevel()) ? "medium" : semantic.riskLevel();
        String level = rank(rules.riskLevel()) >= rank(modelLevel) ? rules.riskLevel() : modelLevel;
        List<String> types = Stream.concat(rules.riskTypes().stream(), semantic.riskTypes().stream())
                .distinct().limit(5).toList();
        List<String> reasons = Stream.concat(rules.reasons().stream(), semantic.reasons().stream())
                .distinct().limit(5).toList();
        List<String> suggestions = Stream.concat(rules.suggestions().stream(), semantic.suggestions().stream())
                .distinct().limit(5).toList();
        return new ProductRiskSignal(level, types, reasons, suggestions,
                rules.needsManualReview() || (!priceOnly && semantic.needsManualReview()));
    }

    private static int rank(String level) {
        return switch (level) {
            case "high" -> 2;
            case "medium" -> 1;
            default -> 0;
        };
    }

    private static ProductRiskResponse response(ProductRiskSignal signal, String mode, String notice) {
        return new ProductRiskResponse(signal.riskLevel(), signal.riskTypes(), signal.reasons(),
                signal.suggestions(), signal.needsManualReview(), mode, notice);
    }
}
