package com.campus.campus_trade_backend.product.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ProductSearchOutboxMapper {
    @Insert("""
            INSERT INTO product_search_outbox (product_id, product_version, event_type)
            SELECT id, version, #{type} FROM product WHERE id = #{productId}
            """)
    int enqueue(@Param("productId") long productId, @Param("type") String type);

    @Select("""
            SELECT id, attempts FROM product_search_outbox
            WHERE (status IN ('PENDING', 'RETRY', 'SENDING') OR
              (status = 'PUBLISHED' AND NOT EXISTS
                (SELECT 1 FROM product_search_consumed c WHERE c.event_id = product_search_outbox.id)))
              AND next_attempt_at <= CURRENT_TIMESTAMP
            ORDER BY next_attempt_at, id LIMIT #{limit}
            """)
    List<Candidate> due(@Param("limit") int limit);

    @Update("""
            UPDATE product_search_outbox SET status = 'SENDING', attempts = attempts + 1,
              next_attempt_at = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP)
            WHERE id = #{id} AND (status IN ('PENDING', 'RETRY', 'SENDING') OR
              (status = 'PUBLISHED' AND NOT EXISTS
                (SELECT 1 FROM product_search_consumed c WHERE c.event_id = product_search_outbox.id)))
              AND next_attempt_at <= CURRENT_TIMESTAMP AND attempts = #{attempts}
            """)
    int claim(@Param("id") long id, @Param("attempts") int attempts);

    @Update("""
            UPDATE product_search_outbox SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
              next_attempt_at = TIMESTAMPADD(SECOND, 60, CURRENT_TIMESTAMP)
            WHERE id = #{id} AND status = 'SENDING' AND attempts = #{attempts}
            """)
    int published(@Param("id") long id, @Param("attempts") int attempts);

    @Update("""
            UPDATE product_search_outbox SET status = #{status},
              next_attempt_at = TIMESTAMPADD(SECOND, #{delaySeconds}, CURRENT_TIMESTAMP)
            WHERE id = #{id} AND status = 'SENDING' AND attempts = #{attempts}
            """)
    int failed(@Param("id") long id, @Param("attempts") int attempts,
               @Param("status") String status, @Param("delaySeconds") long delaySeconds);

    @Select("SELECT id, product_id, product_version, event_type FROM product_search_outbox WHERE id = #{id}")
    Event find(@Param("id") long id);

    @Select("SELECT COUNT(*) FROM product_search_consumed WHERE event_id = #{id}")
    int consumed(@Param("id") long id);

    @Insert("INSERT IGNORE INTO product_search_consumed (event_id) VALUES (#{id})")
    int markConsumed(@Param("id") long id);

    record Candidate(long id, int attempts) { }
    record Event(long id, long productId, int productVersion, String eventType) { }
}
