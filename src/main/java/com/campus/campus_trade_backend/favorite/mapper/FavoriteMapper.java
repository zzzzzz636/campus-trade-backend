package com.campus.campus_trade_backend.favorite.mapper;

import com.campus.campus_trade_backend.favorite.entity.FavoriteProductEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FavoriteMapper {
    @Insert("""
            INSERT INTO product_favorite (user_id, product_id, created_at)
            VALUES (#{userId}, #{productId}, CURRENT_TIMESTAMP)
            """)
    int insert(@Param("userId") long userId, @Param("productId") long productId);

    @Delete("DELETE FROM product_favorite WHERE user_id = #{userId} AND product_id = #{productId}")
    int delete(@Param("userId") long userId, @Param("productId") long productId);

    @Select("SELECT COUNT(*) FROM product_favorite WHERE user_id = #{userId} AND product_id = #{productId}")
    int exists(@Param("userId") long userId, @Param("productId") long productId);

    @Select("SELECT product_id FROM product_favorite WHERE user_id = #{userId}")
    List<Long> listProductIds(long userId);

    @Select("""
            SELECT p.id, p.seller_id, u.nickname AS seller_nickname, c.name AS category_name,
                   p.title, p.price, p.condition_level, p.status,
                   (SELECT pi.id FROM product_image pi WHERE pi.product_id = p.id
                    ORDER BY pi.sort_order, pi.id LIMIT 1) AS cover_image_id,
                   f.created_at AS favorited_at
            FROM product_favorite f
            JOIN product p ON p.id = f.product_id
            JOIN app_user u ON u.id = p.seller_id
            JOIN category c ON c.id = p.category_id
            WHERE f.user_id = #{userId}
            ORDER BY f.created_at DESC, p.id DESC
            LIMIT #{offset}, #{size}
            """)
    List<FavoriteProductEntity> list(@Param("userId") long userId, @Param("offset") int offset,
                                     @Param("size") int size);
}
