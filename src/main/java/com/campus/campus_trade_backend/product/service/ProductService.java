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
import com.campus.campus_trade_backend.product.dto.ProductSort;
import com.campus.campus_trade_backend.product.dto.UpdateProductRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.math.BigDecimal;

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
        return list(page, size, q, null, null, null, ProductSort.NEWEST.name());
    }

    public ProductsPageResponse list(int page, int size, String q, Long categoryId,
                                     BigDecimal minPrice, BigDecimal maxPrice, String sortValue) {
        String keyword = q == null ? "" : q.trim();
        if (keyword.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索词不能超过100字");
        }
        if (categoryId != null && categoryId < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "categoryId 必须大于 0");
        }
        validatePriceRange(minPrice, maxPrice);
        ProductSort sort = ProductSort.from(sortValue);
        String pattern = keyword.isEmpty() ? null : "%" + keyword.toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        long offset = ((long) page - 1) * size;
        long total = productMapper.countOnSale(pattern, categoryId, minPrice, maxPrice);
        List<ProductSummaryResponse> items = offset >= total ? List.of() :
                productMapper.listOnSale(size, offset, pattern, categoryId, minPrice, maxPrice, sort.name())
                        .stream().map(ProductService::toSummary).toList();
        return new ProductsPageResponse(items, page, size, total);
    }

    public List<MyProductResponse> listMine(long sellerId) {
        return productMapper.listBySeller(sellerId).stream()
                .map(product -> new MyProductResponse(product.getId(), product.getCategoryId(), product.getCategoryName(),
                        product.getTitle(), product.getPrice(), product.getConditionLevel(), product.getStatus(),
                        imageUrl(product.getCoverImageId()), product.getCreatedAt(), product.getUpdatedAt()))
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
        validateCategory(request.categoryId());
        ProductEntity product = product(request.categoryId(), request.title(), request.description(),
                request.price(), request.conditionLevel().name());
        product.setSellerId(sellerId);
        productMapper.insert(product);
        return product.getId();
    }

    public ProductDetailResponse update(long id, long sellerId, UpdateProductRequest request) {
        ProductEntity existing = productMapper.findAnyById(id);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        if (!existing.getSellerId().equals(sellerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能编辑自己的商品");
        }
        if (!"ON_SALE".equals(existing.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有在售商品可以编辑");
        }
        validateCategory(request.categoryId());
        ProductEntity changes = product(request.categoryId(), request.title(), request.description(),
                request.price(), request.conditionLevel().name());
        if (productMapper.updateOnSale(id, sellerId, changes) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品状态已变化，请刷新后重试");
        }
        ProductEntity updated = productMapper.findVisibleById(id, sellerId);
        if (updated == null) {
            throw new IllegalStateException("商品更新后无法读取：" + id);
        }
        return toDetail(updated);
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
        return new ProductSummaryResponse(product.getId(), product.getCategoryId(), product.getCategoryName(), product.getTitle(),
                product.getPrice(), product.getConditionLevel(), product.getSellerNickname(),
                imageUrl(product.getCoverImageId()), product.getCreatedAt());
    }

    private ProductDetailResponse toDetail(ProductEntity product) {
        return new ProductDetailResponse(product.getId(), product.getSellerId(), product.getSellerNickname(),
                product.getCategoryId(), product.getCategoryName(),
                product.getTitle(), product.getDescription(), product.getPrice(), product.getConditionLevel(), product.getStatus(),
                productImageMapper.listByProductId(product.getId()).stream()
                        .map(image -> new com.campus.campus_trade_backend.product.dto.ProductImageResponse(
                                image.getId(), imageUrl(image.getId()), image.getOriginalName(), image.getContentType(),
                                image.getFileSize(), image.getSortOrder()))
                        .toList(), product.getCreatedAt(), product.getUpdatedAt());
    }

    private void validateCategory(Long categoryId) {
        if (categoryId == null || categoryId < 1 || categoryMapper.countById(categoryId) == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分类不存在");
        }
    }

    private static ProductEntity product(Long categoryId, String title, String description,
                                         BigDecimal price, String conditionLevel) {
        ProductEntity product = new ProductEntity();
        product.setCategoryId(categoryId);
        product.setTitle(title.trim());
        product.setDescription(description.trim());
        product.setPrice(price);
        product.setConditionLevel(conditionLevel);
        return product;
    }

    private static void validatePriceRange(BigDecimal minPrice, BigDecimal maxPrice) {
        if ((minPrice != null && minPrice.signum() < 0) || (maxPrice != null && maxPrice.signum() < 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "价格区间不能为负数");
        }
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最低价格不能高于最高价格");
        }
    }

    private static String imageUrl(Long imageId) {
        return imageId == null ? null : "/api/product-images/" + imageId + "/content";
    }
}
