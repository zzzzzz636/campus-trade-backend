package com.campus.campus_trade_backend.behavior;

import com.campus.campus_trade_backend.product.mapper.ProductMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/products/hot")
@ConditionalOnProperty(name = "campus.kafka.enabled", havingValue = "true")
public class HotProductsController {
    private final BehaviorRedisStore store;
    private final ProductMapper products;

    public HotProductsController(BehaviorRedisStore store, ProductMapper products) {
        this.store = store;
        this.products = products;
    }

    @GetMapping
    public List<HotProductResponse> list(@RequestParam(defaultValue = "10") @Min(1) @Max(50) int size) {
        return store.top(Math.max(100, size * 4), Instant.now()).stream()
                .map(item -> {
                    var product = products.findOnSaleById(item.productId());
                    return product == null ? null : new HotProductResponse(product.getId(),
                            product.getTitle(), item.score());
                })
                .filter(java.util.Objects::nonNull).limit(size).toList();
    }

    public record HotProductResponse(long productId, String title, long score) { }
}
