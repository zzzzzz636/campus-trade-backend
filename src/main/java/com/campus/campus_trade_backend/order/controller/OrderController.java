package com.campus.campus_trade_backend.order.controller;

import com.campus.campus_trade_backend.order.dto.CreateOrderRequest;
import com.campus.campus_trade_backend.order.dto.OrderDecisionRequest;
import com.campus.campus_trade_backend.order.dto.OrderDetailResponse;
import com.campus.campus_trade_backend.order.dto.OrderResponse;
import com.campus.campus_trade_backend.order.dto.ShipOrderRequest;
import com.campus.campus_trade_backend.order.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateOrderRequest request) {
        return orderService.create(request, Long.parseLong(jwt.getSubject()));
    }

    @GetMapping("/me")
    public List<OrderResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return orderService.listMine(Long.parseLong(jwt.getSubject()));
    }

    @GetMapping("/purchases")
    public List<OrderResponse> purchases(@AuthenticationPrincipal Jwt jwt) {
        return orderService.listPurchases(Long.parseLong(jwt.getSubject()));
    }

    @GetMapping("/sales")
    public List<OrderResponse> sales(@AuthenticationPrincipal Jwt jwt) {
        return orderService.listSales(Long.parseLong(jwt.getSubject()));
    }

    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long id) {
        return orderService.cancel(id, Long.parseLong(jwt.getSubject()));
    }

    @PostMapping("/{id}/accept")
    public OrderResponse accept(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long id,
                                @Valid @RequestBody(required = false) OrderDecisionRequest request) {
        return orderService.accept(id, Long.parseLong(jwt.getSubject()), request == null ? null : request.note());
    }

    @PostMapping("/{id}/reject")
    public OrderResponse reject(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long id,
                                @Valid @RequestBody(required = false) OrderDecisionRequest request) {
        return orderService.reject(id, Long.parseLong(jwt.getSubject()), request == null ? null : request.note());
    }

    @PostMapping("/{id}/ship")
    public OrderResponse ship(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long id,
                              @Valid @RequestBody ShipOrderRequest request) {
        return orderService.ship(id, Long.parseLong(jwt.getSubject()), request);
    }

    @PostMapping("/{id}/complete")
    public OrderResponse complete(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long id) {
        return orderService.complete(id, Long.parseLong(jwt.getSubject()));
    }

    @GetMapping("/{id}")
    public OrderDetailResponse detail(@AuthenticationPrincipal Jwt jwt, @PathVariable @Min(1) long id) {
        return orderService.findDetail(id, Long.parseLong(jwt.getSubject()));
    }
}
