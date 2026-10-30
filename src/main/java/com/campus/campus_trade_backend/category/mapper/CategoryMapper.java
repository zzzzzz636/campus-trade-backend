package com.campus.campus_trade_backend.category.mapper;

import com.campus.campus_trade_backend.category.entity.CategoryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CategoryMapper {
    @Select("SELECT id, name, sort_order FROM category ORDER BY sort_order, id")
    List<CategoryEntity> list();

    @Select("SELECT COUNT(*) FROM category WHERE id = #{id}")
    int countById(@Param("id") long id);
}
