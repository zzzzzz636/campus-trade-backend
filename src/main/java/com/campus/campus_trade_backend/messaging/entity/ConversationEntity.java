package com.campus.campus_trade_backend.messaging.entity;

import java.time.LocalDateTime;

public class ConversationEntity {
    private Long id;
    private String contextType;
    private Long contextId;
    private Long participantLowId;
    private Long participantHighId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String contextTitle;
    private Long otherUserId;
    private String otherNickname;
    private String lastMessage;
    private LocalDateTime lastMessageAt;
    private Long unreadCount;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getContextType() { return contextType; }
    public void setContextType(String contextType) { this.contextType = contextType; }
    public Long getContextId() { return contextId; }
    public void setContextId(Long contextId) { this.contextId = contextId; }
    public Long getParticipantLowId() { return participantLowId; }
    public void setParticipantLowId(Long participantLowId) { this.participantLowId = participantLowId; }
    public Long getParticipantHighId() { return participantHighId; }
    public void setParticipantHighId(Long participantHighId) { this.participantHighId = participantHighId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getContextTitle() { return contextTitle; }
    public void setContextTitle(String contextTitle) { this.contextTitle = contextTitle; }
    public Long getOtherUserId() { return otherUserId; }
    public void setOtherUserId(Long otherUserId) { this.otherUserId = otherUserId; }
    public String getOtherNickname() { return otherNickname; }
    public void setOtherNickname(String otherNickname) { this.otherNickname = otherNickname; }
    public String getLastMessage() { return lastMessage; }
    public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }
    public LocalDateTime getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(LocalDateTime lastMessageAt) { this.lastMessageAt = lastMessageAt; }
    public Long getUnreadCount() { return unreadCount; }
    public void setUnreadCount(Long unreadCount) { this.unreadCount = unreadCount; }
}
