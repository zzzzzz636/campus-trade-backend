package com.campus.campus_trade_backend.messaging.service;

import com.campus.campus_trade_backend.messaging.dto.ConversationContextType;
import com.campus.campus_trade_backend.messaging.dto.ConversationResponse;
import com.campus.campus_trade_backend.messaging.dto.MessagePageResponse;
import com.campus.campus_trade_backend.messaging.dto.MessageResponse;
import com.campus.campus_trade_backend.messaging.dto.StartConversationRequest;
import com.campus.campus_trade_backend.messaging.entity.ConversationEntity;
import com.campus.campus_trade_backend.messaging.entity.MessageEntity;
import com.campus.campus_trade_backend.messaging.mapper.ConversationMapper;
import com.campus.campus_trade_backend.messaging.mapper.MessageMapper;
import com.campus.campus_trade_backend.order.entity.OrderEntity;
import com.campus.campus_trade_backend.order.mapper.OrderMapper;
import com.campus.campus_trade_backend.product.entity.ProductEntity;
import com.campus.campus_trade_backend.product.mapper.ProductMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class MessagingService {
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final MessageRateLimiter rateLimiter;

    public MessagingService(ConversationMapper conversationMapper, MessageMapper messageMapper,
                            ProductMapper productMapper, OrderMapper orderMapper,
                            MessageRateLimiter rateLimiter) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public ConversationResponse start(StartConversationRequest request, long userId) {
        long otherId = resolveOtherParticipant(request.contextType(), request.contextId(), userId);
        long low = Math.min(userId, otherId);
        long high = Math.max(userId, otherId);
        ConversationEntity conversation = conversationMapper.findUnique(
                request.contextType().name(), request.contextId(), low, high);
        if (conversation == null) {
            conversation = new ConversationEntity();
            conversation.setContextType(request.contextType().name());
            conversation.setContextId(request.contextId());
            conversation.setParticipantLowId(low);
            conversation.setParticipantHighId(high);
            try {
                conversationMapper.insert(conversation);
            } catch (DuplicateKeyException race) {
                conversation = conversationMapper.findUnique(request.contextType().name(), request.contextId(), low, high);
            }
        }
        return toResponse(requireParticipant(conversation.getId(), userId));
    }

    public List<ConversationResponse> list(long userId, int page, int size) {
        int offset = (page - 1) * size;
        return conversationMapper.listForUser(userId, offset, size).stream()
                .map(MessagingService::toResponse).toList();
    }

    public MessagePageResponse messages(long conversationId, long userId, Long beforeId, int size) {
        return messages(conversationId, userId, beforeId, null, size);
    }

    public MessagePageResponse messages(long conversationId, long userId, Long beforeId, Long afterId, int size) {
        requireParticipant(conversationId, userId);
        if (beforeId != null && afterId != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "beforeId 与 afterId 不能同时使用");
        }
        List<MessageEntity> rows = new ArrayList<>(messageMapper.listPage(
                conversationId, beforeId, afterId, size + 1));
        boolean hasMore = rows.size() > size;
        if (hasMore) rows.remove(rows.size() - 1);
        Long nextBeforeId = null;
        if (afterId == null) {
            nextBeforeId = hasMore && !rows.isEmpty() ? rows.get(rows.size() - 1).getId() : null;
            Collections.reverse(rows);
        }
        return new MessagePageResponse(rows.stream().map(row -> toResponse(row, userId)).toList(),
                nextBeforeId, hasMore);
    }

    @Transactional
    public MessageResponse send(long conversationId, long userId, String rawContent) {
        requireParticipant(conversationId, userId);
        rateLimiter.check(userId);
        String content = rawContent.trim();
        if (content.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息不能为空");
        MessageEntity message = new MessageEntity();
        message.setConversationId(conversationId);
        message.setSenderId(userId);
        message.setContent(content);
        messageMapper.insert(message);
        conversationMapper.touch(conversationId);
        return toResponse(messageMapper.findById(message.getId()), userId);
    }

    @Transactional
    public int markRead(long conversationId, long userId) {
        requireParticipant(conversationId, userId);
        return messageMapper.markRead(conversationId, userId);
    }

    private long resolveOtherParticipant(ConversationContextType type, long contextId, long userId) {
        if (type == ConversationContextType.PRODUCT) {
            ProductEntity product = productMapper.findAnyById(contextId);
            if (product == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
            if (product.getSellerId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能与自己创建商品会话");
            }
            return product.getSellerId();
        }
        OrderEntity order = orderMapper.findById(contextId);
        if (order == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        if (order.getBuyerId().equals(userId)) return order.getSellerId();
        if (order.getSellerId().equals(userId)) return order.getBuyerId();
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有订单参与者可以创建订单会话");
    }

    private ConversationEntity requireParticipant(long conversationId, long userId) {
        ConversationEntity conversation = conversationMapper.findForUserById(conversationId, userId);
        if (conversation == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问此会话");
        return conversation;
    }

    private static ConversationResponse toResponse(ConversationEntity row) {
        return new ConversationResponse(row.getId(), row.getContextType(), row.getContextId(),
                row.getContextTitle(), row.getOtherUserId(), row.getOtherNickname(), row.getLastMessage(),
                row.getLastMessageAt(), row.getUnreadCount() == null ? 0 : row.getUnreadCount(), row.getUpdatedAt());
    }

    private static MessageResponse toResponse(MessageEntity row, long userId) {
        return new MessageResponse(row.getId(), row.getConversationId(), row.getSenderId(),
                row.getSenderNickname(), row.getContent(), row.getSentAt(), row.getReadAt(),
                row.getSenderId().equals(userId));
    }
}
