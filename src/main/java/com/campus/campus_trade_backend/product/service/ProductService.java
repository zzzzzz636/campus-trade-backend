package com.campus.campus_trade_backend.product.service;

import com.campus.campus_trade_backend.product.dto.ProductSummaryResponse;
import com.campus.campus_trade_backend.product.dto.ProductDetailResponse;
import com.campus.campus_trade_backend.product.dto.ProductsPageResponse;
import com.campus.campus_trade_backend.product.dto.MyProductResponse;
import com.campus.campus_trade_backend.product.entity.ProductEntity;
import com.campus.campus_trade_backend.product.mapper.ProductMapper;
import com.campus.campus_trade_backend.product.mapper.ProductImageMapper;
import com.campus.campus_trade_backend.category.mapper.CategoryMapper;
import com.campus.campus_trade_backend.product.dto.CreateProductRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Locale;

@Service
public class ProductService {

    private final ProductMapper productMapper;
    private final CategoryMapper categoryMapper;
    private final ProductImageMapper productImageMapper;

    public ProductService(ProductMapper productMapper, CategoryMapper categoryMapper,
                          ProductImageMapper productImageMapper) {
        this.productMapper = productMapper;
        this.categoryMapper = categoryMapper;
        this.productImageMapper = productImageMapper;
    }

    public ProductsPageResponse list(int page, int size) {
        return list(page, size, "");
    }

    public ProductsPageResponse list(int page, int size, String q) {
        String keyword = q == null ? "" : q.trim();
        if (keyword.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索词不能超过100字");
        }
        String pattern = keyword.isEmpty() ? null : "%" + keyword.toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        long offset = ((long) page - 1) * size;
        long total = productMapper.countOnSale(pattern);
        List<ProductSummaryResponse> items = offset >= total ? List.of() :
                productMapper.listOnSale(size, offset, pattern).stream().map(ProductService::toSummary).toList();
        return new ProductsPageResponse(items, page, size, total);
    }

    public List<MyProductResponse> listMine(long sellerId) {
        return productMapper.listBySeller(sellerId).stream()
                .map(product -> new MyProductResponse(product.getId(), product.getCategoryId(),
                        product.getTitle(), product.getPrice(), product.getConditionLevel(), product.getStatus(),
                        imageUrl(product.getCoverImageId())))
                .toList();
    }

    public Optional<ProductDetailResponse> findById(long id) {
        return Optional.ofNullable(productMapper.findOnSaleById(id)).map(this::toDetail);
    }

    public Optional<ProductDetailResponse> findVisibleById(long id, Long viewerId) {
        ProductEntity product = viewerId == null
                ? productMapper.findOnSaleById(id)
                : productMapper.findVisibleById(id, viewerId);
        return Optional.ofNullable(product).map(this::toDetail);
    }

    public long create(CreateProductRequest request, long sellerId) {
        if (categoryMapper.countById(request.categoryId()) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分类不存在");
        }
        ProductEntity product = new ProductEntity();
        product.setSellerId(sellerId);
        product.setCategoryId(request.categoryId());
        product.setTitle(request.title().trim());
        product.setDescription(request.description().trim());
        product.setPrice(request.price());
        product.setConditionLevel(request.conditionLevel().name());
        productMapper.insert(product);
        return product.getId();
    }

    public void takeDown(long id, long sellerId) {
        ProductEntity product = productMapper.findAnyById(id);
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        if (!product.getSellerId().equals(sellerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能下架自己的商品");
        }
        if ("OFF_SHELF".equals(product.getStatus())) {
            return;
        }
        if (!"ON_SALE".equals(product.getStatus()) || productMapper.takeDown(id, sellerId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品当前无法下架");
        }
    }

    private static ProductSummaryResponse toSummary(ProductEntity product) {
        return new ProductSummaryResponse(product.getId(), product.getCategoryId(), product.getTitle(),
                product.getPrice(), product.getConditionLevel(), product.getSellerNickname(),
                imageUrl(product.getCoverImageId()));
    }

    private ProductDetailResponse toDetail(ProductEntity product) {
        return new ProductDetailResponse(product.getId(), product.getSellerId(), product.getSellerNickname(), product.getCategoryId(),
                product.getTitle(), product.getDescription(), product.getPrice(), product.getConditionLevel(), product.getStatus(),
                productImageMapper.listByProductId(product.getId()).stream()
                        .map(image -> new com.campus.campus_trade_backend.product.dto.ProductImageResponse(
                                image.getId(), imageUrl(image.getId()), image.getOriginalName(), image.getContentType(),
                                image.getFileSize(), image.getSortOrder()))
                        .toList());
    }

    private static String imageUrl(Long imageId) {
        return imageId == null ? null : "/api/product-images/" + imageId + "/content";
    }
}
