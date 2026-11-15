package com.campus.campus_trade_backend.product.mapper;

import com.campus.campus_trade_backend.product.entity.ProductEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProductSearchSnapshotMapper {
    @Select("""
            SELECT id, version, category_id, title, description, price, status, created_at
            FROM product WHERE id = #{id}
            """)
    ProductEntity find(@Param("id") long id);

    @Select("""
            SELECT id, version, category_id, title, description, price, status, created_at
            FROM product WHERE id > #{afterId} ORDER BY id LIMIT #{limit}
            """)
    List<ProductEntity> page(@Param("afterId") long afterId, @Param("limit") int limit);

    @Select("SELECT COALESCE(MAX(id), 0) FROM product_search_outbox")
    long eventWatermark();

    @Select("""
            SELECT DISTINCT product_id FROM product_search_outbox
            WHERE id > #{watermark} ORDER BY product_id
            """)
    List<Long> changedAfter(@Param("watermark") long watermark);
}
