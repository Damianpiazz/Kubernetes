package com.latiendita.productService.product.dto;

import com.latiendita.productService.product.ProductEntity;

public final class ProductMapper {

    private ProductMapper() {}

    public static ProductDto toDto(ProductEntity entity) {
        return new ProductDto(entity.getId(), entity.getTitle(), entity.getImageUrl(),
                entity.getSku(), entity.getPrice(), entity.getQuantity(),
                entity.getCategoryId());
    }
}
