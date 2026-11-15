package com.campus.campus_trade_backend.product.search;

import com.campus.campus_trade_backend.product.dto.ProductSummaryResponse;
import com.campus.campus_trade_backend.product.dto.ProductsPageResponse;
import com.campus.campus_trade_backend.product.service.ProductService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/products/search")
@ConditionalOnProperty(name = "campus.search.enabled", havingValue = "true")
public class ProductSearchController {
    private final ElasticsearchProductIndex index;
    private final ProductService products;
    private final MeterRegistry metrics;

    public ProductSearchController(ElasticsearchProductIndex index, ProductService products, MeterRegistry metrics) {
        this.index = index;
        this.products = products;
        this.metrics = metrics;
    }

    @GetMapping
    public ProductsPageResponse search(@RequestParam(defaultValue = "") String q,
                                       @RequestParam(required = false) Long categoryId,
                                       @RequestParam(required = false) @DecimalMin("0.00") BigDecimal minPrice,
                                       @RequestParam(required = false) @DecimalMin("0.00") BigDecimal maxPrice,
                                       @RequestParam(defaultValue = "1") @Min(1) @Max(200) int page,
                                       @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        if (q.length() > 100 || categoryId != null && categoryId < 1
                || minPrice != null && minPrice.signum() < 0 || maxPrice != null && maxPrice.signum() < 0
                || minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0
                || page < 1 || page > 200 || size < 1 || size > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索条件不合法");
        }
        if (((long) page - 1) * size > 10000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页范围过大");
        }
        ElasticsearchProductIndex.SearchPage found;
        Timer.Sample sample = Timer.start(metrics);
        try {
            found = index.search(q.trim(), categoryId, minPrice, maxPrice, page, size);
            sample.stop(metrics.timer("campus.search.index.latency", "result", "success"));
        } catch (IllegalStateException unavailable) {
            sample.stop(metrics.timer("campus.search.index.latency", "result", "failure"));
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "搜索服务暂不可用，请使用普通商品列表", unavailable);
        }
        // 索引只提供候选 ID；从 MySQL 当前在售记录构造返回值。
        List<ProductSummaryResponse> items = found.productIds().stream()
                .map(products::findById).flatMap(java.util.Optional::stream)
                .filter(p -> "ON_SALE".equals(p.status()))
                .map(p -> new ProductSummaryResponse(p.id(), p.categoryId(), p.categoryName(),
                        p.title(), p.price(), p.condition(), p.sellerNickname(),
                        p.images().isEmpty() ? null : p.images().get(0).url(), p.createdAt()))
                .toList();
        return new ProductsPageResponse(items, page, size, found.total());
    }
}
