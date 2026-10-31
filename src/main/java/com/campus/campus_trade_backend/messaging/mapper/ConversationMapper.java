package com.campus.campus_trade_backend.messaging.mapper;

import com.campus.campus_trade_backend.messaging.entity.ConversationEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ConversationMapper {
    @Select("""
            SELECT * FROM conversation
            WHERE context_type = #{contextType} AND context_id = #{contextId}
              AND participant_low_id = #{lowId} AND participant_high_id = #{highId}
            """)
    ConversationEntity findUnique(@Param("contextType") String contextType, @Param("contextId") long contextId,
                                  @Param("lowId") long lowId, @Param("highId") long highId);

    @Select("SELECT * FROM conversation WHERE id = #{id}")
    ConversationEntity findById(long id);

    @Select("""
            SELECT c.*,
              CASE WHEN c.participant_low_id = #{userId} THEN c.participant_high_id ELSE c.participant_low_id END AS other_user_id,
              u.nickname AS other_nickname,
              CASE WHEN c.context_type = 'PRODUCT'
                   THEN (SELECT p.title FROM product p WHERE p.id = c.context_id)
                   ELSE (SELECT o.product_title_snapshot FROM trade_order o WHERE o.id = c.context_id) END AS context_title,
              (SELECT m.content FROM private_message m WHERE m.conversation_id = c.id ORDER BY m.id DESC LIMIT 1) AS last_message,
              (SELECT m.sent_at FROM private_message m WHERE m.conversation_id = c.id ORDER BY m.id DESC LIMIT 1) AS last_message_at,
              (SELECT COUNT(*) FROM private_message m
                 WHERE m.conversation_id = c.id AND m.sender_id <> #{userId} AND m.read_at IS NULL) AS unread_count
            FROM conversation c
            JOIN app_user u ON u.id = CASE WHEN c.participant_low_id = #{userId}
                                           THEN c.participant_high_id ELSE c.participant_low_id END
            WHERE c.id = #{id} AND (c.participant_low_id = #{userId} OR c.participant_high_id = #{userId})
            """)
    ConversationEntity findForUserById(@Param("id") long id, @Param("userId") long userId);

    @Insert("""
            INSERT INTO conversation
              (context_type, context_id, participant_low_id, participant_high_id, created_at, updated_at)
            VALUES (#{contextType}, #{contextId}, #{participantLowId}, #{participantHighId},
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ConversationEntity conversation);

    @Select("""
            SELECT c.*,
              CASE WHEN c.participant_low_id = #{userId} THEN c.participant_high_id ELSE c.participant_low_id END AS other_user_id,
              u.nickname AS other_nickname,
              CASE WHEN c.context_type = 'PRODUCT'
                   THEN (SELECT p.title FROM product p WHERE p.id = c.context_id)
                   ELSE (SELECT o.product_title_snapshot FROM trade_order o WHERE o.id = c.context_id) END AS context_title,
              (SELECT m.content FROM private_message m WHERE m.conversation_id = c.id ORDER BY m.id DESC LIMIT 1) AS last_message,
              (SELECT m.sent_at FROM private_message m WHERE m.conversation_id = c.id ORDER BY m.id DESC LIMIT 1) AS last_message_at,
              (SELECT COUNT(*) FROM private_message m
                 WHERE m.conversation_id = c.id AND m.sender_id <> #{userId} AND m.read_at IS NULL) AS unread_count
            FROM conversation c
            JOIN app_user u ON u.id = CASE WHEN c.participant_low_id = #{userId}
                                           THEN c.participant_high_id ELSE c.participant_low_id END
            WHERE c.participant_low_id = #{userId} OR c.participant_high_id = #{userId}
            ORDER BY c.updated_at DESC, c.id DESC
            LIMIT #{offset}, #{size}
            """)
    List<ConversationEntity> listForUser(@Param("userId") long userId, @Param("offset") int offset,
                                         @Param("size") int size);

    @Update("UPDATE conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = #{id}")
    int touch(long id);
}
