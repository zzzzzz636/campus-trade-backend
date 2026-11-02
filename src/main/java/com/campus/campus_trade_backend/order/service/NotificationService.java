package com.campus.campus_trade_backend.order.service;

import com.campus.campus_trade_backend.order.dto.NotificationsPageResponse;
import com.campus.campus_trade_backend.order.mapper.NotificationMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class NotificationService {
    private final NotificationMapper mapper;

    public NotificationService(NotificationMapper mapper) { this.mapper = mapper; }

    public NotificationsPageResponse list(long userId, int page, int size) {
        long total = mapper.countForUser(userId);
        return new NotificationsPageResponse(mapper.listForUser(userId, (page - 1) * size, size),
                total, page, size, (long) page * size < total);
    }

    public long unreadCount(long userId) { return mapper.unreadCount(userId); }

    @Transactional
    public void markRead(long userId, long notificationId) {
        if (mapper.countOwned(notificationId, userId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "通知不存在");
        }
        mapper.markRead(notificationId, userId);
    }
}
