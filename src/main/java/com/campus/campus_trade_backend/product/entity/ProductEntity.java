package com.campus.campus_trade_backend.product.entity;

import java.math.BigDecimal;

public class ProductEntity {
    private Long id;
    private Long sellerId;
    private String sellerNickname;
    private Long categoryId;
    private String title;
    private String description;
    private BigDecimal price;
    private String conditionLevel;
    private String status;
    private Long coverImageId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSellerId() { return sellerId; }
    public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
    public String getSellerNickname() { return sellerNickname; }
    public void setSellerNickname(String sellerNickname) { this.sellerNickname = sellerNickname; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getConditionLevel() { return conditionLevel; }
    public void setConditionLevel(String conditionLevel) { this.conditionLevel = conditionLevel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCoverImageId() { return coverImageId; }
    public void setCoverImageId(Long coverImageId) { this.coverImageId = coverImageId; }
}
