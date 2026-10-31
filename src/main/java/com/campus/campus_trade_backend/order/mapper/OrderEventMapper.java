package com.campus.campus_trade_backend.order.mapper;

import com.campus.campus_trade_backend.order.entity.OrderEventEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface OrderEventMapper {
    @Insert("""
            INSERT INTO order_event (order_id, actor_id, event_type, from_status, to_status, note, created_at)
            VALUES (#{orderId}, #{actorId}, #{eventType}, #{fromStatus}, #{toStatus}, #{note}, CURRENT_TIMESTAMP)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OrderEventEntity event);

    @Select("""
            SELECT id, order_id, actor_id, event_type, from_status, to_status, note, created_at
            FROM order_event WHERE order_id = #{orderId}
            ORDER BY created_at, id
            """)
    List<OrderEventEntity> listByOrder(@Param("orderId") long orderId);
}
