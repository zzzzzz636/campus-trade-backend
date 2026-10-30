package com.campus.campus_trade_backend.product.mapper;

import com.campus.campus_trade_backend.product.entity.ProductEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Options;

import java.util.List;
import java.math.BigDecimal;

@Mapper
public interface ProductMapper {
    @Select("""
            <script>
            SELECT COUNT(*) FROM product p WHERE p.status = 'ON_SALE'
            <if test='pattern != null'>
              AND (LOWER(p.title) LIKE #{pattern} ESCAPE '!'
                   OR LOWER(p.description) LIKE #{pattern} ESCAPE '!')
            </if>
            <if test='categoryId != null'>AND p.category_id = #{categoryId}</if>
            <if test='minPrice != null'>AND p.price &gt;= #{minPrice}</if>
            <if test='maxPrice != null'>AND p.price &lt;= #{maxPrice}</if>
            </script>
            """)
    long countOnSale(@Param("pattern") String pattern,
                     @Param("categoryId") Long categoryId,
                     @Param("minPrice") BigDecimal minPrice,
                     @Param("maxPrice") BigDecimal maxPrice);

    @Select("""
            <script>
            SELECT p.id, p.seller_id, u.nickname AS seller_nickname, p.category_id,
                   c.name AS category_name, p.title, p.description, p.price, p.condition_level,
                   p.created_at,
                   (SELECT pi.id FROM product_image pi WHERE pi.product_id = p.id
                    ORDER BY pi.sort_order, pi.id LIMIT 1) AS cover_image_id
            FROM product p
            JOIN app_user u ON u.id = p.seller_id
            JOIN category c ON c.id = p.category_id
            WHERE p.status = 'ON_SALE'
            <if test='pattern != null'>
              AND (LOWER(p.title) LIKE #{pattern} ESCAPE '!'
                   OR LOWER(p.description) LIKE #{pattern} ESCAPE '!')
            </if>
            <if test='categoryId != null'>AND p.category_id = #{categoryId}</if>
            <if test='minPrice != null'>AND p.price &gt;= #{minPrice}</if>
            <if test='maxPrice != null'>AND p.price &lt;= #{maxPrice}</if>
            ORDER BY
            <choose>
              <when test='sort == "PRICE_ASC"'>p.price ASC, p.created_at DESC,</when>
              <when test='sort == "PRICE_DESC"'>p.price DESC, p.created_at DESC,</when>
              <otherwise>p.created_at DESC,</otherwise>
            </choose>
            p.id DESC
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<ProductEntity> listOnSale(@Param("size") int size, @Param("offset") long offset,
                                   @Param("pattern") String pattern,
                                   @Param("categoryId") Long categoryId,
                                   @Param("minPrice") BigDecimal minPrice,
                                   @Param("maxPrice") BigDecimal maxPrice,
                                   @Param("sort") String sort);

    @Select("""
            SELECT p.id, p.seller_id, p.category_id, c.name AS category_name,
                   p.title, p.price, p.condition_level, p.status, p.created_at, p.updated_at,
                   (SELECT pi.id FROM product_image pi WHERE pi.product_id = p.id
                    ORDER BY pi.sort_order, pi.id LIMIT 1) AS cover_image_id
            FROM product p JOIN category c ON c.id = p.category_id
            WHERE p.seller_id = #{sellerId}
            ORDER BY p.created_at DESC, p.id DESC LIMIT 100
            """)
    List<ProductEntity> listBySeller(@Param("sellerId") long sellerId);

    @Select("""
            SELECT p.id, p.seller_id, u.nickname AS seller_nickname, p.category_id,
                   c.name AS category_name, p.title, p.description, p.price, p.condition_level, p.status,
                   p.created_at, p.updated_at
            FROM product p JOIN app_user u ON u.id = p.seller_id
            JOIN category c ON c.id = p.category_id
            WHERE p.id = #{id} AND p.status = 'ON_SALE'
            """)
    ProductEntity findOnSaleById(@Param("id") long id);

    @Select("""
            SELECT p.id, p.seller_id, u.nickname AS seller_nickname, p.category_id,
                   c.name AS category_name, p.title, p.description, p.price, p.condition_level, p.status,
                   p.created_at, p.updated_at
            FROM product p JOIN app_user u ON u.id = p.seller_id
            JOIN category c ON c.id = p.category_id
            WHERE p.id = #{id} AND (p.status = 'ON_SALE' OR p.seller_id = #{viewerId})
            """)
    ProductEntity findVisibleById(@Param("id") long id, @Param("viewerId") long viewerId);

    @Select("""
            SELECT id, seller_id, category_id, title, description, price, condition_level, status
            FROM product WHERE id = #{id}
            """)
    ProductEntity findAnyById(@Param("id") long id);

    @Select("""
            SELECT id, seller_id, category_id, title, description, price, condition_level, status
            FROM product WHERE id = #{id} FOR UPDATE
            """)
    ProductEntity findAnyByIdForUpdate(@Param("id") long id);

    @Insert("""
            INSERT INTO product (seller_id, category_id, title, description, price,
                                 condition_level, status, created_at, updated_at)
            VALUES (#{sellerId}, #{categoryId}, #{title}, #{description}, #{price},
                    #{conditionLevel}, 'ON_SALE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ProductEntity product);

    @Update("""
            UPDATE product
            SET category_id = #{product.categoryId}, title = #{product.title},
                description = #{product.description}, price = #{product.price},
                condition_level = #{product.conditionLevel}, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND seller_id = #{sellerId} AND status = 'ON_SALE'
            """)
    int updateOnSale(@Param("id") long id, @Param("sellerId") long sellerId,
                     @Param("product") ProductEntity product);

    @Update("""
            UPDATE product SET status = 'OFF_SHELF', updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND seller_id = #{sellerId} AND status = 'ON_SALE'
            """)
    int takeDown(@Param("id") long id, @Param("sellerId") long sellerId);

    @Update("""
            UPDATE product SET status = 'RESERVED', updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND status = 'ON_SALE' AND seller_id <> #{buyerId}
            """)
    int reserveForOrder(@Param("id") long id, @Param("buyerId") long buyerId);

    @Update("""
            UPDATE product SET status = 'ON_SALE', updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND status = 'RESERVED'
            """)
    int releaseAfterCancellation(@Param("id") long id);

    @Update("""
            UPDATE product SET status = 'SOLD', updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND status = 'RESERVED'
            """)
    int markSold(@Param("id") long id);
}
