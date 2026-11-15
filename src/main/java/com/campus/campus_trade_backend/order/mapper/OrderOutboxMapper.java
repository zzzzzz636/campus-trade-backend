package com.campus.campus_trade_backend.order.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface OrderOutboxMapper {
    @Insert("""
            INSERT INTO order_notification_outbox (event_key, order_id, recipient_id, event_type, title)
            VALUES (#{key}, #{orderId}, #{recipientId}, #{type}, #{title})
            """)
    int enqueue(@Param("key") String key, @Param("orderId") long orderId,
                @Param("recipientId") long recipientId, @Param("type") String type,
                @Param("title") String title);

    @Select("""
            SELECT id, attempts FROM order_notification_outbox
            WHERE status IN ('PENDING', 'RETRY', 'SENDING') AND next_attempt_at <= CURRENT_TIMESTAMP
            ORDER BY next_attempt_at, id LIMIT #{limit}
            """)
    List<OutboxCandidate> due(@Param("limit") int limit);

    @Update("""
            UPDATE order_notification_outbox SET status = 'SENDING', attempts = attempts + 1,
              next_attempt_at = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP)
            WHERE id = #{id} AND status IN ('PENDING', 'RETRY', 'SENDING')
              AND next_attempt_at <= CURRENT_TIMESTAMP AND attempts = #{previousAttempts}
            """)
    int claim(@Param("id") long id, @Param("previousAttempts") int previousAttempts);

    @Update("""
            UPDATE order_notification_outbox SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND status = 'SENDING' AND attempts = #{attempts}
            """)
    int published(@Param("id") long id, @Param("attempts") int attempts);

    @Update("""
            UPDATE order_notification_outbox SET status = #{status},
              next_attempt_at = TIMESTAMPADD(SECOND, #{delaySeconds}, CURRENT_TIMESTAMP)
            WHERE id = #{id} AND status = 'SENDING' AND attempts = #{attempts}
            """)
    int failed(@Param("id") long id, @Param("attempts") int attempts,
               @Param("status") String status, @Param("delaySeconds") long delaySeconds);

    @Select("""
            SELECT id, order_id, recipient_id, event_type, title, status, attempts
            FROM order_notification_outbox WHERE id = #{id}
            """)
    OutboxEvent find(@Param("id") long id);

    @Insert("INSERT IGNORE INTO order_notification_consumed (event_id) VALUES (#{eventId})")
    int markConsumed(@Param("eventId") long eventId);

    record OutboxCandidate(long id, int attempts) { }
    record OutboxEvent(long id, long orderId, long recipientId, String eventType,
                       String title, String status, int attempts) { }
}
