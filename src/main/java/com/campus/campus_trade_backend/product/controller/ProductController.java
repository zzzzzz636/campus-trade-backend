package com.campus.campus_trade_backend.product.controller;

import com.campus.campus_trade_backend.product.dto.ProductsPageResponse;
import com.campus.campus_trade_backend.product.dto.MyProductResponse;
import com.campus.campus_trade_backend.product.dto.ProductDetailResponse;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import com.campus.campus_trade_backend.product.dto.ProductCreatedResponse;
import com.campus.campus_trade_backend.product.service.ProductService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public ProductsPageResponse list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size,
            @RequestParam(defaultValue = "") String q
    ) {
        return productService.list(page, size, q);
    }

    @GetMapping("/me")
    public List<MyProductResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return productService.listMine(Long.parseLong(jwt.getSubject()));
    }

    @GetMapping("/{id}")
    public ProductDetailResponse detail(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long id) {
        Long viewerId = jwt == null ? null : Long.parseLong(jwt.getSubject());
        return productService.findVisibleById(id, viewerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductCreatedResponse create(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody CreateProductRequest request) {
        return new ProductCreatedResponse(productService.create(request, Long.parseLong(jwt.getSubject())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void takeDown(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long id) {
        productService.takeDown(id, Long.parseLong(jwt.getSubject()));
    }
}
