package com.campus.campus_trade_backend.order.mapper;

import com.campus.campus_trade_backend.order.dto.CancellationRequestResponse;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CancellationRequestMapper {
    @Select("""
            SELECT id, order_id, requester_id, status, reason, created_at, resolved_at
            FROM order_cancel_request WHERE order_id = #{orderId} AND status = 'PENDING'
            ORDER BY id DESC LIMIT 1
            """)
    CancellationRequestResponse findPending(@Param("orderId") long orderId);

    @Insert("""
            INSERT INTO order_cancel_request (order_id, requester_id, status, reason, created_at)
            VALUES (#{orderId}, #{requesterId}, 'PENDING', #{reason}, CURRENT_TIMESTAMP)
            """)
    int insert(@Param("orderId") long orderId, @Param("requesterId") long requesterId,
               @Param("reason") String reason);

    @Update("""
            UPDATE order_cancel_request SET status = #{status}, resolved_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int resolve(@Param("id") long id, @Param("status") String status);
}
