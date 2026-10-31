package com.campus.campus_trade_backend.favorite.service;

import com.campus.campus_trade_backend.favorite.dto.FavoriteProductResponse;
import com.campus.campus_trade_backend.favorite.mapper.FavoriteMapper;
import com.campus.campus_trade_backend.product.mapper.ProductMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class FavoriteService {
    private final FavoriteMapper favoriteMapper;
    private final ProductMapper productMapper;

    public FavoriteService(FavoriteMapper favoriteMapper, ProductMapper productMapper) {
        this.favoriteMapper = favoriteMapper;
        this.productMapper = productMapper;
    }

    @Transactional
    public void add(long userId, long productId) {
        if (productMapper.findAnyById(productId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        if (favoriteMapper.exists(userId, productId) > 0) return;
        try {
            favoriteMapper.insert(userId, productId);
        } catch (DuplicateKeyException ignored) {
            // 唯一键使并发重复收藏保持幂等。
        }
    }

    @Transactional
    public void remove(long userId, long productId) {
        favoriteMapper.delete(userId, productId);
    }

    public List<Long> productIds(long userId) {
        return favoriteMapper.listProductIds(userId);
    }

    public List<FavoriteProductResponse> list(long userId, int page, int size) {
        return favoriteMapper.list(userId, (page - 1) * size, size).stream()
                .map(row -> new FavoriteProductResponse(row.getId(), row.getSellerId(), row.getSellerNickname(),
                        row.getCategoryName(), row.getTitle(), row.getPrice(), row.getConditionLevel(), row.getStatus(),
                        row.getCoverImageId() == null ? null : "/api/product-images/" + row.getCoverImageId() + "/content",
                        row.getFavoritedAt()))
                .toList();
    }
}
