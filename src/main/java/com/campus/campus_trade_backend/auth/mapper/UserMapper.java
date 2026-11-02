package com.campus.campus_trade_backend.auth.mapper;

import com.campus.campus_trade_backend.auth.entity.UserEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMapper {
    @Select("SELECT id, email, password_hash, nickname, status, token_version FROM app_user WHERE email = #{email}")
    UserEntity findByEmail(@Param("email") String email);

    @Select("SELECT id, email, password_hash, nickname, status, token_version FROM app_user WHERE id = #{id}")
    UserEntity findById(@Param("id") long id);

    @Insert("""
            INSERT INTO app_user (email, password_hash, nickname, status, created_at)
            VALUES (#{email}, #{passwordHash}, #{nickname}, 'ACTIVE', CURRENT_TIMESTAMP)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserEntity user);

    @Update("UPDATE app_user SET nickname = #{nickname} WHERE id = #{id} AND status = 'ACTIVE'")
    int updateNickname(@Param("id") long id, @Param("nickname") String nickname);

    @Update("UPDATE app_user SET password_hash = #{hash}, token_version = token_version + 1 WHERE id = #{id} AND status = 'ACTIVE'")
    int updatePassword(@Param("id") long id, @Param("hash") String hash);

    @Update("UPDATE app_user SET password_hash = #{hash}, token_version = token_version + 1 WHERE id = #{id} AND password_hash = #{oldHash} AND status = 'ACTIVE'")
    int changePassword(@Param("id") long id, @Param("oldHash") String oldHash, @Param("hash") String hash);
}
