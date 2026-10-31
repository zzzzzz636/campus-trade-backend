package com.campus.campus_trade_backend.favorite.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class FavoriteProductEntity {
    private Long id;
    private Long sellerId;
    private String sellerNickname;
    private String categoryName;
    private String title;
    private BigDecimal price;
    private String conditionLevel;
    private String status;
    private Long coverImageId;
    private LocalDateTime favoritedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSellerId() { return sellerId; }
    public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
    public String getSellerNickname() { return sellerNickname; }
    public void setSellerNickname(String sellerNickname) { this.sellerNickname = sellerNickname; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getConditionLevel() { return conditionLevel; }
    public void setConditionLevel(String conditionLevel) { this.conditionLevel = conditionLevel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCoverImageId() { return coverImageId; }
    public void setCoverImageId(Long coverImageId) { this.coverImageId = coverImageId; }
    public LocalDateTime getFavoritedAt() { return favoritedAt; }
    public void setFavoritedAt(LocalDateTime favoritedAt) { this.favoritedAt = favoritedAt; }
}
