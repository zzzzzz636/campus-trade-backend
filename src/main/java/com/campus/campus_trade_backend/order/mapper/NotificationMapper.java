package com.campus.campus_trade_backend.order.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NotificationMapper {
    @Insert("""
            INSERT INTO app_notification (user_id, type, reference_id, title, created_at)
            VALUES (#{userId}, #{type}, #{referenceId}, #{title}, CURRENT_TIMESTAMP)
            """)
    int insert(@Param("userId") long userId, @Param("type") String type,
               @Param("referenceId") long referenceId, @Param("title") String title);
}
