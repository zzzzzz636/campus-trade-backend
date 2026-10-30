package com.campus.campus_trade_backend.product.mapper;

import com.campus.campus_trade_backend.product.entity.ProductImageEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProductImageMapper {
    @Select("""
            SELECT id, product_id, storage_key, original_name, content_type, file_size, sort_order
            FROM product_image WHERE product_id = #{productId}
            ORDER BY sort_order, id
            """)
    List<ProductImageEntity> listByProductId(@Param("productId") long productId);

    @Select("""
            SELECT id, product_id, storage_key, original_name, content_type, file_size, sort_order
            FROM product_image WHERE id = #{id}
            """)
    ProductImageEntity findById(@Param("id") long id);

    @Select("SELECT COUNT(*) FROM product_image WHERE product_id = #{productId}")
    int countByProductId(@Param("productId") long productId);

    @Insert("""
            INSERT INTO product_image
                (product_id, storage_key, original_name, content_type, file_size, sort_order, created_at)
            VALUES
                (#{productId}, #{storageKey}, #{originalName}, #{contentType}, #{fileSize}, #{sortOrder}, CURRENT_TIMESTAMP)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ProductImageEntity image);

    @Delete("DELETE FROM product_image WHERE id = #{id} AND product_id = #{productId}")
    int delete(@Param("id") long id, @Param("productId") long productId);
}
