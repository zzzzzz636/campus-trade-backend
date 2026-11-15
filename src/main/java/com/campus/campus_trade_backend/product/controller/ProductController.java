package com.campus.campus_trade_backend.product.controller;

import com.campus.campus_trade_backend.product.dto.ProductsPageResponse;
import com.campus.campus_trade_backend.product.dto.MyProductResponse;
import com.campus.campus_trade_backend.product.dto.ProductDetailResponse;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.dto.ProductCreatedResponse;
import com.campus.campus_trade_backend.product.dto.UpdateProductRequest;
import com.campus.campus_trade_backend.product.service.ProductService;
import com.campus.campus_trade_backend.behavior.BehaviorRecorder;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import java.util.List;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final BehaviorRecorder behaviorRecorder;

    public ProductController(ProductService productService, BehaviorRecorder behaviorRecorder) {
        this.productService = productService;
        this.behaviorRecorder = behaviorRecorder;
    }

    @GetMapping
    public ProductsPageResponse list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) @Min(1) Long categoryId,
            @RequestParam(required = false) @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal minPrice,
            @RequestParam(required = false) @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal maxPrice,
            @RequestParam(defaultValue = "NEWEST") String sort
    ) {
        return productService.list(page, size, q, categoryId, minPrice, maxPrice, sort);
    }

    @GetMapping("/me")
    public List<MyProductResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return productService.listMine(Long.parseLong(jwt.getSubject()));
    }

    @GetMapping("/{id}")
    public ProductDetailResponse detail(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long id) {
        Long viewerId = jwt == null ? null : Long.parseLong(jwt.getSubject());
        ProductDetailResponse detail = productService.findVisibleById(id, viewerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在"));
        if ("ON_SALE".equals(detail.status())) behaviorRecorder.view(id);
        return detail;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductCreatedResponse create(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody CreateProductRequest request) {
        return new ProductCreatedResponse(productService.create(request, Long.parseLong(jwt.getSubject())));
    }

    @PutMapping("/{id}")
    public ProductDetailResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long id,
                                        @Valid @RequestBody UpdateProductRequest request) {
        return productService.update(id, Long.parseLong(jwt.getSubject()), request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void takeDown(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long id) {
        productService.takeDown(id, Long.parseLong(jwt.getSubject()));
    }

    @PostMapping("/{id}/relist")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void relist(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long id) {
        productService.relist(id, Long.parseLong(jwt.getSubject()));
    }
}
