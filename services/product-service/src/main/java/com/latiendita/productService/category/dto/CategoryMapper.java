package com.latiendita.productService.category.dto;

import com.latiendita.productService.category.CategoryEntity;

public final class CategoryMapper {

    private CategoryMapper() {}

    public static CategoryDto toDto(CategoryEntity entity) {
        return new CategoryDto(entity.getId(), entity.getName(), entity.getDescription());
    }
}
