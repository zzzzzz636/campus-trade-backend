package com.campus.campus_trade_backend.favorite.controller;

import com.campus.campus_trade_backend.favorite.dto.FavoriteProductResponse;
import com.campus.campus_trade_backend.favorite.service.FavoriteService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class FavoriteController {
    private final FavoriteService favoriteService;

    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    @PostMapping("/products/{productId}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long productId) {
        favoriteService.add(userId(jwt), productId);
    }

    @DeleteMapping("/products/{productId}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long productId) {
        favoriteService.remove(userId(jwt), productId);
    }

    @GetMapping("/favorites")
    public List<FavoriteProductResponse> list(@AuthenticationPrincipal Jwt jwt,
                                              @RequestParam(defaultValue = "1") @Min(1) int page,
                                              @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return favoriteService.list(userId(jwt), page, size);
    }

    @GetMapping("/favorites/product-ids")
    public List<Long> productIds(@AuthenticationPrincipal Jwt jwt) {
        return favoriteService.productIds(userId(jwt));
    }

    private static long userId(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
}
