package com.campus.campus_trade_backend.category.controller;

import com.campus.campus_trade_backend.category.entity.CategoryEntity;
import com.campus.campus_trade_backend.category.mapper.CategoryMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {
    private final CategoryMapper categoryMapper;

    public CategoryController(CategoryMapper categoryMapper) {
        this.categoryMapper = categoryMapper;
    }

    @GetMapping
    public List<CategoryEntity> list() {
        return categoryMapper.list();
    }
}
