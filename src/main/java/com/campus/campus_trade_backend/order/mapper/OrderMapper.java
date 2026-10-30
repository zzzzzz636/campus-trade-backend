package com.campus.campus_trade_backend.order.mapper;

import com.campus.campus_trade_backend.order.entity.OrderEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface OrderMapper {
    @Insert("""
            INSERT INTO trade_order (product_id, buyer_id, seller_id, price, status, request_key, created_at)
            VALUES (#{productId}, #{buyerId}, #{sellerId}, #{price}, #{status}, #{requestKey}, CURRENT_TIMESTAMP)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OrderEntity order);

    @Select("""
            SELECT o.id, o.product_id, p.title AS product_title,
                   o.buyer_id, o.seller_id, o.price, o.status
            FROM trade_order o JOIN product p ON p.id = o.product_id
            WHERE o.id = #{id}
            """)
    OrderEntity findById(@Param("id") long id);

    @Select("""
            SELECT o.id, o.product_id, p.title AS product_title,
                   o.buyer_id, o.seller_id, o.price, o.status
            FROM trade_order o JOIN product p ON p.id = o.product_id
            WHERE o.buyer_id = #{userId} OR o.seller_id = #{userId}
            ORDER BY o.created_at DESC, o.id DESC LIMIT 50
            """)
    List<OrderEntity> listForUser(@Param("userId") long userId);

    @Select("""
            SELECT o.id, o.product_id, p.title AS product_title,
                   o.buyer_id, o.seller_id, o.price, o.status
            FROM trade_order o JOIN product p ON p.id = o.product_id
            WHERE o.buyer_id = #{buyerId}
            ORDER BY o.created_at DESC, o.id DESC LIMIT 50
            """)
    List<OrderEntity> listForBuyer(@Param("buyerId") long buyerId);

    @Select("""
            SELECT o.id, o.product_id, p.title AS product_title,
                   o.buyer_id, o.seller_id, o.price, o.status
            FROM trade_order o JOIN product p ON p.id = o.product_id
            WHERE o.seller_id = #{sellerId}
            ORDER BY o.created_at DESC, o.id DESC LIMIT 50
            """)
    List<OrderEntity> listForSeller(@Param("sellerId") long sellerId);

    @Update("""
            UPDATE trade_order SET status = 'CANCELLED'
            WHERE id = #{id} AND buyer_id = #{buyerId} AND status = 'PENDING_MEETUP'
            """)
    int cancelPending(@Param("id") long id, @Param("buyerId") long buyerId);

    @Update("""
            UPDATE trade_order SET status = 'COMPLETED'
            WHERE id = #{id} AND buyer_id = #{buyerId} AND status = 'PENDING_MEETUP'
            """)
    int completePending(@Param("id") long id, @Param("buyerId") long buyerId);
}
