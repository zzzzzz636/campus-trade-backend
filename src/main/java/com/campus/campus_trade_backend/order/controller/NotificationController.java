package com.campus.campus_trade_backend.order.controller;

import com.campus.campus_trade_backend.order.dto.NotificationsPageResponse;
import com.campus.campus_trade_backend.order.service.NotificationService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) { this.service = service; }

    @GetMapping
    public NotificationsPageResponse list(@AuthenticationPrincipal Jwt jwt,
                                          @RequestParam(defaultValue = "1") @Min(1) @Max(1000) int page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return service.list(Long.parseLong(jwt.getSubject()), page, size);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("count", service.unreadCount(Long.parseLong(jwt.getSubject())));
    }

    @PostMapping("/{id}/read")
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long id) {
        service.markRead(Long.parseLong(jwt.getSubject()), id);
    }
}
