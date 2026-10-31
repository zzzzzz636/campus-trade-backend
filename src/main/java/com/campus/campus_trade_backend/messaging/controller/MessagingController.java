package com.campus.campus_trade_backend.messaging.controller;

import com.campus.campus_trade_backend.messaging.dto.ConversationResponse;
import com.campus.campus_trade_backend.messaging.dto.MessagePageResponse;
import com.campus.campus_trade_backend.messaging.dto.MessageResponse;
import com.campus.campus_trade_backend.messaging.dto.SendMessageRequest;
import com.campus.campus_trade_backend.messaging.dto.StartConversationRequest;
import com.campus.campus_trade_backend.messaging.service.MessagingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class MessagingController {
    private final MessagingService messagingService;

    public MessagingController(MessagingService messagingService) {
        this.messagingService = messagingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse start(@AuthenticationPrincipal Jwt jwt,
                                      @Valid @RequestBody StartConversationRequest request) {
        return messagingService.start(request, userId(jwt));
    }

    @GetMapping
    public List<ConversationResponse> list(@AuthenticationPrincipal Jwt jwt,
                                           @RequestParam(defaultValue = "1") @Min(1) int page,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return messagingService.list(userId(jwt), page, size);
    }

    @GetMapping("/{id}/messages")
    public MessagePageResponse messages(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long id,
                                        @RequestParam(required = false) @Min(1) Long beforeId,
                                        @RequestParam(defaultValue = "30") @Min(1) @Max(100) int size) {
        return messagingService.messages(id, userId(jwt), beforeId, size);
    }

    @PostMapping("/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageResponse send(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long id,
                                @Valid @RequestBody SendMessageRequest request) {
        return messagingService.send(id, userId(jwt), request.content());
    }

    @PostMapping("/{id}/read")
    public void markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long id) {
        messagingService.markRead(id, userId(jwt));
    }

    private static long userId(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
}
