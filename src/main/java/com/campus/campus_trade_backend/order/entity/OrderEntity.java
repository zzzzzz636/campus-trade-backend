package com.campus.campus_trade_backend.order.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class OrderEntity {
    private Long id;
    private Long productId;
    private String productTitle;
    private String productDescription;
    private Long productCoverImageId;
    private Long buyerId;
    private String buyerNickname;
    private Long sellerId;
    private String sellerNickname;
    private BigDecimal price;
    private String status;
    private String fulfillmentType;
    private String buyerNote;
    private String handoffLocation;
    private String recipientName;
    private String recipientPhone;
    private String shippingAddress;
    private String shippingCarrier;
    private String trackingNumber;
    private String sellerDecisionNote;
    private String requestKey;
    private LocalDateTime expiresAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime rejectedAt;
    private LocalDateTime shippedAt;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductTitle() { return productTitle; }
    public void setProductTitle(String productTitle) { this.productTitle = productTitle; }
    public String getProductDescription() { return productDescription; }
    public void setProductDescription(String productDescription) { this.productDescription = productDescription; }
    public Long getProductCoverImageId() { return productCoverImageId; }
    public void setProductCoverImageId(Long productCoverImageId) { this.productCoverImageId = productCoverImageId; }
    public Long getBuyerId() { return buyerId; }
    public void setBuyerId(Long buyerId) { this.buyerId = buyerId; }
    public String getBuyerNickname() { return buyerNickname; }
    public void setBuyerNickname(String buyerNickname) { this.buyerNickname = buyerNickname; }
    public Long getSellerId() { return sellerId; }
    public void setSellerId(Long sellerId) { this.sellerId = sellerId; }
    public String getSellerNickname() { return sellerNickname; }
    public void setSellerNickname(String sellerNickname) { this.sellerNickname = sellerNickname; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFulfillmentType() { return fulfillmentType; }
    public void setFulfillmentType(String fulfillmentType) { this.fulfillmentType = fulfillmentType; }
    public String getBuyerNote() { return buyerNote; }
    public void setBuyerNote(String buyerNote) { this.buyerNote = buyerNote; }
    public String getHandoffLocation() { return handoffLocation; }
    public void setHandoffLocation(String handoffLocation) { this.handoffLocation = handoffLocation; }
    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }
    public String getRecipientPhone() { return recipientPhone; }
    public void setRecipientPhone(String recipientPhone) { this.recipientPhone = recipientPhone; }
    public String getShippingAddress() { return shippingAddress; }
    public void setShippingAddress(String shippingAddress) { this.shippingAddress = shippingAddress; }
    public String getShippingCarrier() { return shippingCarrier; }
    public void setShippingCarrier(String shippingCarrier) { this.shippingCarrier = shippingCarrier; }
    public String getTrackingNumber() { return trackingNumber; }
    public void setTrackingNumber(String trackingNumber) { this.trackingNumber = trackingNumber; }
    public String getSellerDecisionNote() { return sellerDecisionNote; }
    public void setSellerDecisionNote(String sellerDecisionNote) { this.sellerDecisionNote = sellerDecisionNote; }
    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String requestKey) { this.requestKey = requestKey; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(LocalDateTime acceptedAt) { this.acceptedAt = acceptedAt; }
    public LocalDateTime getRejectedAt() { return rejectedAt; }
    public void setRejectedAt(LocalDateTime rejectedAt) { this.rejectedAt = rejectedAt; }
    public LocalDateTime getShippedAt() { return shippedAt; }
    public void setShippedAt(LocalDateTime shippedAt) { this.shippedAt = shippedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
