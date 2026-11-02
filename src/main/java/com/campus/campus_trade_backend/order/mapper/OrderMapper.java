package com.campus.campus_trade_backend.order.mapper;

import com.campus.campus_trade_backend.order.entity.OrderEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface OrderMapper {
    @Insert("""
            INSERT INTO trade_order (
                product_id, buyer_id, seller_id, product_title_snapshot, product_description_snapshot,
                price, status, fulfillment_type, buyer_note, handoff_location,
                recipient_name, recipient_phone, shipping_address, request_key, expires_at,
                created_at, updated_at
            ) VALUES (
                #{productId}, #{buyerId}, #{sellerId}, #{productTitle}, #{productDescription},
                #{price}, #{status}, #{fulfillmentType}, #{buyerNote}, #{handoffLocation},
                #{recipientName}, #{recipientPhone}, #{shippingAddress}, #{requestKey}, #{expiresAt},
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OrderEntity order);

    @Select("""
            SELECT o.id, o.product_id, o.product_title_snapshot AS product_title,
                   o.product_description_snapshot AS product_description,
                   (SELECT pi.id FROM product_image pi WHERE pi.product_id = o.product_id
                    ORDER BY pi.sort_order, pi.id LIMIT 1) AS product_cover_image_id,
                   o.buyer_id, buyer.nickname AS buyer_nickname,
                   o.seller_id, seller.nickname AS seller_nickname,
                   o.price, o.status, o.fulfillment_type, o.buyer_note, o.handoff_location,
                   o.recipient_name, o.recipient_phone, o.shipping_address,
                   o.shipping_carrier, o.tracking_number, o.seller_decision_note, o.request_key,
                   o.expires_at, o.accepted_at, o.rejected_at, o.shipped_at,
                   o.completed_at, o.cancelled_at, o.created_at, o.updated_at
            FROM trade_order o
            JOIN app_user buyer ON buyer.id = o.buyer_id
            JOIN app_user seller ON seller.id = o.seller_id
            WHERE o.id = #{id}
            """)
    OrderEntity findById(@Param("id") long id);

    @Select("""
            SELECT id, product_id, buyer_id, seller_id,
                   product_title_snapshot AS product_title,
                   product_description_snapshot AS product_description,
                   price, status, fulfillment_type, buyer_note, handoff_location,
                   recipient_name, recipient_phone, shipping_address,
                   shipping_carrier, tracking_number, seller_decision_note, request_key,
                   expires_at, accepted_at, rejected_at, shipped_at, completed_at,
                   cancelled_at, created_at, updated_at
            FROM trade_order WHERE id = #{id} FOR UPDATE
            """)
    OrderEntity findByIdForUpdate(@Param("id") long id);

    @Select("""
            SELECT o.id, o.product_id, o.product_title_snapshot AS product_title,
                   (SELECT pi.id FROM product_image pi WHERE pi.product_id = o.product_id
                    ORDER BY pi.sort_order, pi.id LIMIT 1) AS product_cover_image_id,
                   o.buyer_id, buyer.nickname AS buyer_nickname,
                   o.seller_id, seller.nickname AS seller_nickname,
                   o.price, o.status, o.fulfillment_type, o.created_at, o.updated_at
            FROM trade_order o
            JOIN app_user buyer ON buyer.id = o.buyer_id
            JOIN app_user seller ON seller.id = o.seller_id
            WHERE o.buyer_id = #{userId} OR o.seller_id = #{userId}
            ORDER BY o.created_at DESC, o.id DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<OrderEntity> listForUser(@Param("userId") long userId, @Param("offset") int offset,
                                  @Param("limit") int limit);

    @Select("""
            <script>
            SELECT o.id, o.product_id, o.product_title_snapshot AS product_title,
                   (SELECT pi.id FROM product_image pi WHERE pi.product_id = o.product_id
                    ORDER BY pi.sort_order, pi.id LIMIT 1) AS product_cover_image_id,
                   o.buyer_id, buyer.nickname AS buyer_nickname,
                   o.seller_id, seller.nickname AS seller_nickname,
                   o.price, o.status, o.fulfillment_type, o.created_at, o.updated_at
            FROM trade_order o
            JOIN app_user buyer ON buyer.id = o.buyer_id
            JOIN app_user seller ON seller.id = o.seller_id
            WHERE o.buyer_id = #{buyerId}
            <if test="status != null">AND o.status = #{status}</if>
            ORDER BY o.created_at DESC, o.id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<OrderEntity> listForBuyer(@Param("buyerId") long buyerId, @Param("status") String status,
                                   @Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            <script>SELECT COUNT(*) FROM trade_order
            WHERE buyer_id = #{buyerId}
            <if test="status != null">AND status = #{status}</if>
            </script>
            """)
    long countForBuyer(@Param("buyerId") long buyerId, @Param("status") String status);

    @Select("""
            <script>
            SELECT o.id, o.product_id, o.product_title_snapshot AS product_title,
                   (SELECT pi.id FROM product_image pi WHERE pi.product_id = o.product_id
                    ORDER BY pi.sort_order, pi.id LIMIT 1) AS product_cover_image_id,
                   o.buyer_id, buyer.nickname AS buyer_nickname,
                   o.seller_id, seller.nickname AS seller_nickname,
                   o.price, o.status, o.fulfillment_type, o.created_at, o.updated_at
            FROM trade_order o
            JOIN app_user buyer ON buyer.id = o.buyer_id
            JOIN app_user seller ON seller.id = o.seller_id
            WHERE o.seller_id = #{sellerId}
            <if test="status != null">AND o.status = #{status}</if>
            ORDER BY o.created_at DESC, o.id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<OrderEntity> listForSeller(@Param("sellerId") long sellerId, @Param("status") String status,
                                    @Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            <script>SELECT COUNT(*) FROM trade_order
            WHERE seller_id = #{sellerId}
            <if test="status != null">AND status = #{status}</if>
            </script>
            """)
    long countForSeller(@Param("sellerId") long sellerId, @Param("status") String status);

    @Select("""
            SELECT id, product_id, buyer_id, seller_id,
                   product_title_snapshot AS product_title, product_description_snapshot AS product_description,
                   price, status, fulfillment_type, request_key, created_at, updated_at
            FROM trade_order WHERE buyer_id = #{buyerId} AND request_key = #{requestKey}
            """)
    OrderEntity findByBuyerAndRequestKey(@Param("buyerId") long buyerId,
                                         @Param("requestKey") String requestKey);

    @Update("""
            UPDATE trade_order
            SET status = #{targetStatus}, seller_decision_note = #{note},
                accepted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND seller_id = #{sellerId}
              AND status = 'PENDING_SELLER_CONFIRMATION'
              AND (expires_at IS NULL OR expires_at > #{now})
            """)
    int acceptPending(@Param("id") long id, @Param("sellerId") long sellerId,
                      @Param("targetStatus") String targetStatus, @Param("note") String note,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE trade_order
            SET status = 'REJECTED', seller_decision_note = #{note},
                rejected_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND seller_id = #{sellerId}
              AND status = 'PENDING_SELLER_CONFIRMATION'
            """)
    int rejectPending(@Param("id") long id, @Param("sellerId") long sellerId,
                      @Param("note") String note);

    @Update("""
            UPDATE trade_order
            SET status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND buyer_id = #{buyerId}
              AND status = 'PENDING_SELLER_CONFIRMATION'
            """)
    int cancelPending(@Param("id") long id, @Param("buyerId") long buyerId);

    @Update("""
            UPDATE trade_order
            SET status = 'SHIPPED', shipping_carrier = #{carrier}, tracking_number = #{trackingNumber},
                shipped_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND seller_id = #{sellerId} AND status = 'WAITING_SHIPMENT'
            """)
    int markShipped(@Param("id") long id, @Param("sellerId") long sellerId,
                    @Param("carrier") String carrier, @Param("trackingNumber") String trackingNumber);

    @Update("""
            UPDATE trade_order
            SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND buyer_id = #{buyerId}
              AND status IN ('WAITING_HANDOFF', 'SHIPPED')
            """)
    int completeFulfillment(@Param("id") long id, @Param("buyerId") long buyerId);

    @Select("""
            SELECT id FROM trade_order
            WHERE status = 'PENDING_SELLER_CONFIRMATION' AND expires_at <= #{now}
            ORDER BY expires_at, id LIMIT #{limit}
            """)
    List<Long> findExpiredPendingIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE trade_order
            SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND status = 'PENDING_SELLER_CONFIRMATION' AND expires_at <= #{now}
            """)
    int expirePending(@Param("id") long id, @Param("now") LocalDateTime now);
}
