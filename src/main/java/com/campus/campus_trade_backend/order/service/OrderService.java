package com.campus.campus_trade_backend.order.service;

import com.campus.campus_trade_backend.order.dto.OrderResponse;
import com.campus.campus_trade_backend.order.entity.OrderEntity;
import com.campus.campus_trade_backend.order.mapper.OrderMapper;
import com.campus.campus_trade_backend.product.entity.ProductEntity;
import com.campus.campus_trade_backend.product.mapper.ProductMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class OrderService {
    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;

    public OrderService(ProductMapper productMapper, OrderMapper orderMapper) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
    }

    @Transactional
    public OrderResponse create(long productId, long buyerId) {
        return create(productId, buyerId, null);
    }

    @Transactional
    public OrderResponse create(long productId, long buyerId, String requestKey) {
        // 锁定商品行，避免卖家编辑与买家下单交错后，订单保存了过期标题或价格。
        ProductEntity product = productMapper.findAnyByIdForUpdate(productId);
        if (product == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        }
        if (product.getSellerId().equals(buyerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能购买自己的商品");
        }
        if (!"ON_SALE".equals(product.getStatus()) ||
                productMapper.reserveForOrder(productId, buyerId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品已被选购或下架");
        }

        OrderEntity order = new OrderEntity();
        order.setProductId(productId);
        order.setProductTitle(product.getTitle());
        order.setBuyerId(buyerId);
        order.setSellerId(product.getSellerId());
        order.setPrice(product.getPrice());
        order.setStatus("PENDING_MEETUP");
        order.setRequestKey(requestKey);
        try {
            // 唯一请求键冲突会抛异常，使前面的商品预订更新在同一事务中回滚。
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该下单请求已处理，请勿重复提交", e);
        }
        return toResponse(order);
    }

    @Transactional
    public OrderResponse cancel(long orderId, long buyerId) {
        OrderEntity order = requireBuyerOrder(orderId, buyerId);
        if (!"PENDING_MEETUP".equals(order.getStatus()) || orderMapper.cancelPending(orderId, buyerId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单当前状态不能取消");
        }
        // 订单和商品状态必须一起成功；异常让 Spring 回滚整个事务。
        if (productMapper.releaseAfterCancellation(order.getProductId()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品状态异常，取消订单已回滚");
        }
        order.setStatus("CANCELLED");
        return toResponse(order);
    }

    @Transactional
    public OrderResponse complete(long orderId, long buyerId) {
        OrderEntity order = requireBuyerOrder(orderId, buyerId);
        if (!"PENDING_MEETUP".equals(order.getStatus()) || orderMapper.completePending(orderId, buyerId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单当前状态不能确认成交");
        }
        if (productMapper.markSold(order.getProductId()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品状态异常，确认成交已回滚");
        }
        order.setStatus("COMPLETED");
        return toResponse(order);
    }

    private OrderEntity requireBuyerOrder(long orderId, long buyerId) {
        OrderEntity order = orderMapper.findById(orderId);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        if (!order.getBuyerId().equals(buyerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能操作自己的购买订单");
        }
        return order;
    }

    public OrderResponse findById(long orderId, long userId) {
        OrderEntity order = orderMapper.findById(orderId);
        if (order == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        }
        if (!order.getBuyerId().equals(userId) && !order.getSellerId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看此订单");
        }
        return toResponse(order);
    }

    public List<OrderResponse> listMine(long userId) {
        return orderMapper.listForUser(userId).stream().map(OrderService::toResponse).toList();
    }

    public List<OrderResponse> listPurchases(long buyerId) {
        return orderMapper.listForBuyer(buyerId).stream().map(OrderService::toResponse).toList();
    }

    public List<OrderResponse> listSales(long sellerId) {
        return orderMapper.listForSeller(sellerId).stream().map(OrderService::toResponse).toList();
    }

    private static OrderResponse toResponse(OrderEntity order) {
        return new OrderResponse(order.getId(), order.getProductId(), order.getProductTitle(), order.getBuyerId(),
                order.getSellerId(), order.getPrice(), order.getStatus());
    }
}
