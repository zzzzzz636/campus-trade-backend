package com.campus.campus_trade_backend.messaging.mapper;

import com.campus.campus_trade_backend.messaging.entity.MessageEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface MessageMapper {
    @Insert("""
            INSERT INTO private_message (conversation_id, sender_id, content, sent_at)
            VALUES (#{conversationId}, #{senderId}, #{content}, CURRENT_TIMESTAMP)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MessageEntity message);

    @Select("""
            SELECT m.*, u.nickname AS sender_nickname
            FROM private_message m JOIN app_user u ON u.id = m.sender_id
            WHERE m.id = #{id}
            """)
    MessageEntity findById(long id);

    @Select("""
            <script>
            SELECT m.*, u.nickname AS sender_nickname
            FROM private_message m JOIN app_user u ON u.id = m.sender_id
            WHERE m.conversation_id = #{conversationId}
            <if test="beforeId != null">AND m.id &lt; #{beforeId}</if>
            <if test="afterId != null">AND m.id &gt; #{afterId}</if>
            <choose>
              <when test="afterId != null">ORDER BY m.id ASC</when>
              <otherwise>ORDER BY m.id DESC</otherwise>
            </choose>
            LIMIT #{limit}
            </script>
            """)
    List<MessageEntity> listPage(@Param("conversationId") long conversationId,
                                 @Param("beforeId") Long beforeId,
                                 @Param("afterId") Long afterId,
                                 @Param("limit") int limit);

    @Update("""
            UPDATE private_message SET read_at = CURRENT_TIMESTAMP
            WHERE conversation_id = #{conversationId} AND sender_id <> #{userId} AND read_at IS NULL
            """)
    int markRead(@Param("conversationId") long conversationId, @Param("userId") long userId);
}
