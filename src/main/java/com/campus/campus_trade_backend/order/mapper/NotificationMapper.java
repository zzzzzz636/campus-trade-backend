package com.campus.campus_trade_backend.order.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.campus.campus_trade_backend.order.dto.NotificationResponse;
import java.util.List;

@Mapper
public interface NotificationMapper {
    @Insert("""
            INSERT INTO app_notification (user_id, type, reference_id, title, created_at)
            VALUES (#{userId}, #{type}, #{referenceId}, #{title}, CURRENT_TIMESTAMP)
            """)
    int insert(@Param("userId") long userId, @Param("type") String type,
               @Param("referenceId") long referenceId, @Param("title") String title);

    @Select("""
            SELECT id, type, reference_id, title, created_at, read_at
            FROM app_notification WHERE user_id = #{userId}
            ORDER BY created_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<NotificationResponse> listForUser(@Param("userId") long userId,
                                            @Param("offset") int offset, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM app_notification WHERE user_id = #{userId}")
    long countForUser(@Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM app_notification WHERE user_id = #{userId} AND read_at IS NULL")
    long unreadCount(@Param("userId") long userId);

    @Update("""
            UPDATE app_notification SET read_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND user_id = #{userId} AND read_at IS NULL
            """)
    int markRead(@Param("id") long id, @Param("userId") long userId);

    @Select("SELECT COUNT(*) FROM app_notification WHERE id = #{id} AND user_id = #{userId}")
    long countOwned(@Param("id") long id, @Param("userId") long userId);
}
