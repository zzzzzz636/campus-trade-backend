package com.campus.campus_trade_backend.order.service;

import com.campus.campus_trade_backend.order.dto.CreateOrderRequest;
import com.campus.campus_trade_backend.order.dto.CancellationRequestResponse;
import com.campus.campus_trade_backend.order.dto.FulfillmentType;
import com.campus.campus_trade_backend.order.dto.OrderDetailResponse;
import com.campus.campus_trade_backend.order.dto.OrderEventResponse;
import com.campus.campus_trade_backend.order.dto.OrderResponse;
import com.campus.campus_trade_backend.order.dto.OrdersPageResponse;
import com.campus.campus_trade_backend.order.dto.ShipOrderRequest;
import com.campus.campus_trade_backend.order.entity.OrderEntity;
import com.campus.campus_trade_backend.order.entity.OrderEventEntity;
import com.campus.campus_trade_backend.order.mapper.NotificationMapper;
import com.campus.campus_trade_backend.order.mapper.CancellationRequestMapper;
import com.campus.campus_trade_backend.order.mapper.OrderEventMapper;
import com.campus.campus_trade_backend.order.mapper.OrderMapper;
import com.campus.campus_trade_backend.order.mapper.OrderOutboxMapper;
import com.campus.campus_trade_backend.product.entity.ProductEntity;
import com.campus.campus_trade_backend.product.mapper.ProductMapper;
import com.campus.campus_trade_backend.product.mapper.ProductSearchOutboxMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final String PENDING = "PENDING_SELLER_CONFIRMATION";
    private static final Set<String> SELLER_MAY_VIEW_SHIPPING =
            Set.of("WAITING_SHIPMENT", "SHIPPED", "COMPLETED");
    private static final Set<String> ORDER_STATUSES = Set.of("PENDING_SELLER_CONFIRMATION",
            "WAITING_HANDOFF", "WAITING_SHIPMENT", "SHIPPED", "COMPLETED",
            "CANCELLED", "REJECTED", "EXPIRED");

    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final OrderEventMapper orderEventMapper;
    private final NotificationMapper notificationMapper;
    private final OrderOutboxMapper outboxMapper;
    private final ProductSearchOutboxMapper searchOutbox;
    private final CancellationRequestMapper cancellationRequestMapper;

    @Value("${campus.order.confirmation-hours:24}")
    private long confirmationHours;
    @Value("${campus.rabbit.enabled:false}")
    private boolean rabbitEnabled;

    public OrderService(ProductMapper productMapper, OrderMapper orderMapper,
                        OrderEventMapper orderEventMapper, NotificationMapper notificationMapper,
                        CancellationRequestMapper cancellationRequestMapper, OrderOutboxMapper outboxMapper,
                        ProductSearchOutboxMapper searchOutbox) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
        this.orderEventMapper = orderEventMapper;
        this.notificationMapper = notificationMapper;
        this.outboxMapper = outboxMapper;
        this.searchOutbox = searchOutbox;
        this.cancellationRequestMapper = cancellationRequestMapper;
    }

    @Transactional
    public OrderResponse create(long productId, long buyerId) {
        return create(productId, buyerId, null);
    }

    @Transactional
    public OrderResponse create(long productId, long buyerId, String requestKey) {
        return create(new CreateOrderRequest(productId, requestKey, FulfillmentType.CAMPUS_HANDOFF,
                null, null, null, null, null), buyerId);
    }

    @Transactional
    public OrderResponse create(CreateOrderRequest request, long buyerId) {
        String requestKey = trimToNull(request.requestKey());
        OrderEntity previous = findIdempotentOrder(buyerId, request.productId(), requestKey);
        if (previous != null) return toResponse(orderMapper.findById(previous.getId()));

        // 商品行锁把“检查在售、写订单快照、预订商品”串成一个原子业务单元。
        ProductEntity product = productMapper.findAnyByIdForUpdate(request.productId());
        if (product == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "商品不存在");
        if (product.getSellerId().equals(buyerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能申请购买自己的商品");
        }
        // MySQL REPEATABLE READ 的普通 SELECT 会沿用先前快照；商品锁后改用当前读，
        // 才能看见等待商品锁期间由另一请求提交的同一请求键订单。
        previous = findIdempotentOrderForUpdate(buyerId, request.productId(), requestKey);
        if (previous != null) return toResponse(orderMapper.findByIdForUpdate(previous.getId()));
        if (!"ON_SALE".equals(product.getStatus()) ||
                productMapper.reserveForOrder(product.getId(), buyerId) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "商品已有待处理申请或已下架");
        }
        searchOutbox.enqueue(product.getId(), "RESERVED");

        FulfillmentType fulfillment = request.fulfillmentType() == null
                ? FulfillmentType.CAMPUS_HANDOFF : request.fulfillmentType();
        validateFulfillment(request, fulfillment);
        OrderEntity order = new OrderEntity();
        order.setProductId(product.getId());
        order.setProductTitle(product.getTitle());
        order.setProductDescription(product.getDescription());
        order.setBuyerId(buyerId);
        order.setSellerId(product.getSellerId());
        order.setPrice(product.getPrice());
        order.setStatus(PENDING);
        order.setFulfillmentType(fulfillment.name());
        order.setBuyerNote(trimToNull(request.buyerNote()));
        order.setHandoffLocation(fulfillment == FulfillmentType.CAMPUS_HANDOFF
                ? trimToNull(request.handoffLocation()) : null);
        order.setRecipientName(fulfillment == FulfillmentType.SHIPPING
                ? request.recipientName().trim() : null);
        order.setRecipientPhone(fulfillment == FulfillmentType.SHIPPING
                ? request.recipientPhone().trim() : null);
        order.setShippingAddress(fulfillment == FulfillmentType.SHIPPING
                ? request.shippingAddress().trim() : null);
        order.setRequestKey(requestKey);
        order.setExpiresAt(LocalDateTime.now().plusHours(confirmationHours));
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该申请请求键已被其他订单使用", duplicate);
        }
        record(order.getId(), buyerId, "BUYER_APPLIED", null, PENDING, null);
        notifyUser(order.getSellerId(), "ORDER_APPLICATION", order.getId(),
                "收到新的购买申请：" + product.getTitle());
        log.info("订单状态转换 orderId={} actorId={} from={} to={}", order.getId(), buyerId, null, PENDING);
        return toResponse(requireOrder(order.getId()));
    }

    @Transactional
    public OrderResponse accept(long orderId, long sellerId, String note) {
        OrderEntity order = requireLockedOrder(orderId);
        requireSeller(order, sellerId);
        if (!PENDING.equals(order.getStatus())) conflict("订单已经处理，不能重复接受");
        LocalDateTime now = LocalDateTime.now();
        if (order.getExpiresAt() != null && !order.getExpiresAt().isAfter(now)) {
            conflict("购买申请已到期，不能接受；等待超时任务释放商品");
        }
        String target = FulfillmentType.SHIPPING.name().equals(order.getFulfillmentType())
                ? "WAITING_SHIPMENT" : "WAITING_HANDOFF";
        if (orderMapper.acceptPending(orderId, sellerId, target, trimToNull(note), now) != 1) {
            conflict("订单状态已变化，请刷新后重试");
        }
        record(orderId, sellerId, "SELLER_ACCEPTED", PENDING, target, trimToNull(note));
        notifyUser(order.getBuyerId(), "ORDER_ACCEPTED", orderId, "卖家已接受你的购买申请");
        log.info("订单状态转换 orderId={} actorId={} from={} to={}", orderId, sellerId, PENDING, target);
        return toResponse(requireOrder(orderId));
    }

    @Transactional
    public OrderResponse reject(long orderId, long sellerId, String note) {
        OrderEntity order = requireLockedOrder(orderId);
        requireSeller(order, sellerId);
        if (!PENDING.equals(order.getStatus()) ||
                orderMapper.rejectPending(orderId, sellerId, trimToNull(note)) != 1) {
            conflict("订单当前状态不能拒绝");
        }
        releaseProduct(order, "拒绝申请");
        record(orderId, sellerId, "SELLER_REJECTED", PENDING, "REJECTED", trimToNull(note));
        notifyUser(order.getBuyerId(), "ORDER_REJECTED", orderId, "卖家未接受你的购买申请");
        log.info("订单状态转换 orderId={} actorId={} from={} to=REJECTED", orderId, sellerId, PENDING);
        return toResponse(requireOrder(orderId));
    }

    @Transactional
    public OrderResponse cancel(long orderId, long buyerId) {
        OrderEntity order = requireLockedOrder(orderId);
        requireBuyer(order, buyerId);
        if (!PENDING.equals(order.getStatus()) || orderMapper.cancelPending(orderId, buyerId) != 1) {
            conflict("卖家接受后不能直接取消，请先联系卖家协商");
        }
        releaseProduct(order, "取消申请");
        record(orderId, buyerId, "BUYER_CANCELLED", PENDING, "CANCELLED", null);
        notifyUser(order.getSellerId(), "ORDER_CANCELLED", orderId, "买家已取消购买申请");
        log.info("订单状态转换 orderId={} actorId={} from={} to=CANCELLED", orderId, buyerId, PENDING);
        return toResponse(requireOrder(orderId));
    }

    @Transactional
    public OrderResponse ship(long orderId, long sellerId, ShipOrderRequest request) {
        OrderEntity order = requireLockedOrder(orderId);
        requireSeller(order, sellerId);
        if (cancellationRequestMapper.findPending(orderId) != null) conflict("协商取消请求待处理，暂不能发货");
        if (!"WAITING_SHIPMENT".equals(order.getStatus()) ||
                orderMapper.markShipped(orderId, sellerId, request.carrier().trim(), request.trackingNumber().trim()) != 1) {
            conflict("只有等待发货的快递订单可以确认发货");
        }
        record(orderId, sellerId, "SELLER_SHIPPED", "WAITING_SHIPMENT", "SHIPPED", "卖家已填写物流信息");
        notifyUser(order.getBuyerId(), "ORDER_SHIPPED", orderId, "卖家已发货，请在收到商品后确认收货");
        log.info("订单状态转换 orderId={} actorId={} from=WAITING_SHIPMENT to=SHIPPED", orderId, sellerId);
        return toResponse(requireOrder(orderId));
    }

    @Transactional
    public OrderResponse complete(long orderId, long buyerId) {
        OrderEntity order = requireLockedOrder(orderId);
        requireBuyer(order, buyerId);
        if (cancellationRequestMapper.findPending(orderId) != null) conflict("协商取消请求待处理，暂不能完成交易");
        String from = order.getStatus();
        if (!("WAITING_HANDOFF".equals(from) || "SHIPPED".equals(from)) ||
                orderMapper.completeFulfillment(orderId, buyerId) != 1) {
            conflict("订单尚未达到可确认收货的状态");
        }
        if (productMapper.markSold(order.getProductId()) != 1) {
            conflict("商品状态异常，确认收货已回滚");
        }
        searchOutbox.enqueue(order.getProductId(), "SOLD");
        record(orderId, buyerId, "BUYER_COMPLETED", from, "COMPLETED", null);
        notifyUser(order.getSellerId(), "ORDER_COMPLETED", orderId, "买家已确认收货，交易完成");
        log.info("订单状态转换 orderId={} actorId={} from={} to=COMPLETED", orderId, buyerId, from);
        return toResponse(requireOrder(orderId));
    }

    public OrderDetailResponse findDetail(long orderId, long userId) {
        OrderEntity order = requireOrder(orderId);
        requireParticipant(order, userId);
        boolean buyer = order.getBuyerId().equals(userId);
        boolean revealShipping = buyer || SELLER_MAY_VIEW_SHIPPING.contains(order.getStatus());
        List<OrderEventResponse> events = orderEventMapper.listByOrder(orderId).stream()
                .map(event -> new OrderEventResponse(event.getId(), event.getActorId(), event.getEventType(),
                        event.getFromStatus(), event.getToStatus(), event.getNote(), event.getCreatedAt()))
                .toList();
        return new OrderDetailResponse(order.getId(), order.getProductId(), order.getProductTitle(),
                order.getProductDescription(), imageUrl(order.getProductCoverImageId()), order.getPrice(),
                order.getStatus(), order.getFulfillmentType(), order.getBuyerId(), order.getBuyerNickname(),
                order.getSellerId(), order.getSellerNickname(), buyer ? "BUYER" : "SELLER",
                order.getBuyerNote(), order.getHandoffLocation(),
                revealShipping ? order.getRecipientName() : null,
                revealShipping ? order.getRecipientPhone() : null,
                revealShipping ? order.getShippingAddress() : null,
                order.getShippingCarrier(), order.getTrackingNumber(), order.getSellerDecisionNote(),
                order.getExpiresAt(), order.getAcceptedAt(), order.getRejectedAt(), order.getShippedAt(),
                order.getCompletedAt(), order.getCancelledAt(), order.getCreatedAt(), order.getUpdatedAt(), events);
    }

    public OrderResponse findById(long orderId, long userId) {
        OrderEntity order = requireOrder(orderId);
        requireParticipant(order, userId);
        return toResponse(order);
    }

    public CancellationRequestResponse pendingCancellation(long orderId, long userId) {
        requireParticipant(requireOrder(orderId), userId);
        return cancellationRequestMapper.findPending(orderId);
    }

    @Transactional
    public CancellationRequestResponse proposeCancellation(long orderId, long userId, String reason) {
        OrderEntity order = requireLockedOrder(orderId);
        requireParticipant(order, userId);
        if (!Set.of("WAITING_HANDOFF", "WAITING_SHIPMENT").contains(order.getStatus())) {
            conflict("只有已接受且尚未发货的订单可以协商取消");
        }
        if (cancellationRequestMapper.findPending(orderId) != null) conflict("已有待处理的协商取消请求");
        cancellationRequestMapper.insert(orderId, userId, reason.trim());
        record(orderId, userId, "CANCELLATION_REQUESTED", order.getStatus(), order.getStatus(), reason.trim());
        long otherId = order.getBuyerId().equals(userId) ? order.getSellerId() : order.getBuyerId();
        notifyUser(otherId, "CANCELLATION_REQUESTED", orderId, "对方请求协商取消订单");
        return cancellationRequestMapper.findPending(orderId);
    }

    @Transactional
    public OrderResponse resolveCancellation(long orderId, long userId, boolean accept) {
        OrderEntity order = requireLockedOrder(orderId);
        requireParticipant(order, userId);
        CancellationRequestResponse pending = cancellationRequestMapper.findPending(orderId);
        if (pending == null) conflict("没有待处理的协商取消请求");
        if (pending.requesterId() == userId) conflict("发起者不能处理自己的请求");
        if (!Set.of("WAITING_HANDOFF", "WAITING_SHIPMENT").contains(order.getStatus())) {
            conflict("订单状态已变化，无法处理协商取消");
        }
        if (accept) {
            if (orderMapper.cancelAfterAgreement(orderId) != 1) conflict("订单状态已变化，取消失败");
            releaseProduct(order, "协商取消");
            if (cancellationRequestMapper.resolve(pending.id(), "ACCEPTED") != 1) conflict("协商请求已处理");
            record(orderId, userId, "CANCELLATION_ACCEPTED", order.getStatus(), "CANCELLED", null);
            notifyUser(pending.requesterId(), "CANCELLATION_ACCEPTED", orderId, "对方同意协商取消，商品已恢复在售");
        } else {
            if (cancellationRequestMapper.resolve(pending.id(), "REJECTED") != 1) conflict("协商请求已处理");
            record(orderId, userId, "CANCELLATION_REJECTED", order.getStatus(), order.getStatus(), null);
            notifyUser(pending.requesterId(), "CANCELLATION_REJECTED", orderId, "对方拒绝协商取消，交易继续");
        }
        return toResponse(requireOrder(orderId));
    }

    public List<OrderResponse> listMine(long userId) {
        return orderMapper.listForUser(userId, 0, 50).stream().map(OrderService::toResponse).toList();
    }

    public List<OrderResponse> listPurchases(long buyerId) {
        return listPurchases(buyerId, 1, 50, null).items();
    }

    public List<OrderResponse> listSales(long sellerId) {
        return listSales(sellerId, 1, 50, null).items();
    }

    public OrdersPageResponse listPurchases(long buyerId, int page, int size, String status) {
        String filter = validatedStatus(status);
        long total = orderMapper.countForBuyer(buyerId, filter);
        List<OrderResponse> items = orderMapper.listForBuyer(buyerId, filter, (page - 1) * size, size)
                .stream().map(OrderService::toResponse).toList();
        return new OrdersPageResponse(items, total, page, size, (long) page * size < total);
    }

    public OrdersPageResponse listSales(long sellerId, int page, int size, String status) {
        String filter = validatedStatus(status);
        long total = orderMapper.countForSeller(sellerId, filter);
        List<OrderResponse> items = orderMapper.listForSeller(sellerId, filter, (page - 1) * size, size)
                .stream().map(OrderService::toResponse).toList();
        return new OrdersPageResponse(items, total, page, size, (long) page * size < total);
    }

    private static String validatedStatus(String status) {
        if (status == null || status.isBlank()) return null;
        if (!ORDER_STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知订单状态");
        }
        return status;
    }

    @Transactional
    public int expirePendingOrders(int limit) {
        LocalDateTime now = LocalDateTime.now();
        int expired = 0;
        for (Long orderId : orderMapper.findExpiredPendingIds(now, limit)) {
            OrderEntity order = requireLockedOrder(orderId);
            if (!PENDING.equals(order.getStatus()) || orderMapper.expirePending(orderId, now) != 1) continue;
            releaseProduct(order, "申请超时");
            record(orderId, null, "APPLICATION_EXPIRED", PENDING, "EXPIRED", null);
            notifyUser(order.getBuyerId(), "ORDER_EXPIRED", orderId, "购买申请已超时失效");
            notifyUser(order.getSellerId(), "ORDER_EXPIRED", orderId, "未处理的购买申请已超时释放商品");
            expired++;
        }
        if (expired > 0) log.info("订单超时任务完成 expiredCount={}", expired);
        return expired;
    }

    private OrderEntity findIdempotentOrder(long buyerId, long productId, String requestKey) {
        if (requestKey == null) return null;
        OrderEntity existing = orderMapper.findByBuyerAndRequestKey(buyerId, requestKey);
        if (existing != null && !existing.getProductId().equals(productId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请求键已用于其他商品");
        }
        return existing;
    }

    private OrderEntity findIdempotentOrderForUpdate(long buyerId, long productId, String requestKey) {
        if (requestKey == null) return null;
        OrderEntity existing = orderMapper.findByBuyerAndRequestKeyForUpdate(buyerId, requestKey);
        if (existing != null && !existing.getProductId().equals(productId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请求键已用于其他商品");
        }
        return existing;
    }

    private void validateFulfillment(CreateOrderRequest request, FulfillmentType fulfillment) {
        if (fulfillment == FulfillmentType.SHIPPING &&
                (isBlank(request.recipientName()) || isBlank(request.recipientPhone()) || isBlank(request.shippingAddress()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "快递订单必须填写收件人、联系电话和收货地址");
        }
    }

    private void releaseProduct(OrderEntity order, String operation) {
        if (productMapper.releaseAfterCancellation(order.getProductId()) != 1) {
            conflict("商品状态异常，" + operation + "已回滚");
        }
        searchOutbox.enqueue(order.getProductId(), "RELEASED");
    }

    private OrderEntity requireOrder(long orderId) {
        OrderEntity order = orderMapper.findById(orderId);
        if (order == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        return order;
    }

    private OrderEntity requireLockedOrder(long orderId) {
        OrderEntity order = orderMapper.findByIdForUpdate(orderId);
        if (order == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        return order;
    }

    private static void requireBuyer(OrderEntity order, long userId) {
        if (!order.getBuyerId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有订单买家可以执行此操作");
        }
    }

    private static void requireSeller(OrderEntity order, long userId) {
        if (!order.getSellerId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有商品卖家可以处理此申请");
        }
    }

    private static void requireParticipant(OrderEntity order, long userId) {
        if (!order.getBuyerId().equals(userId) && !order.getSellerId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看此订单");
        }
    }

    private void record(long orderId, Long actorId, String type, String from, String to, String note) {
        OrderEventEntity event = new OrderEventEntity();
        event.setOrderId(orderId);
        event.setActorId(actorId);
        event.setEventType(type);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setNote(note);
        orderEventMapper.insert(event);
    }

    private void notifyUser(long userId, String type, long orderId, String title) {
        if (rabbitEnabled && "ORDER_APPLICATION".equals(type)) {
            // 与商品预订、订单写入同事务落库；消费者幂等地生成卖家站内通知。
            outboxMapper.enqueue(type + ":" + orderId + ":" + userId, orderId, userId, type, title);
        } else {
            notificationMapper.insert(userId, type, orderId, title);
        }
    }

    private static OrderResponse toResponse(OrderEntity order) {
        return new OrderResponse(order.getId(), order.getProductId(), order.getProductTitle(),
                imageUrl(order.getProductCoverImageId()), order.getBuyerId(), order.getBuyerNickname(),
                order.getSellerId(), order.getSellerNickname(), order.getPrice(), order.getStatus(),
                order.getFulfillmentType(), order.getCreatedAt(), order.getUpdatedAt());
    }

    private static String imageUrl(Long imageId) {
        return imageId == null ? null : "/api/product-images/" + imageId + "/content";
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void conflict(String message) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
