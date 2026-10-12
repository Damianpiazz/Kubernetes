package com.latiendita.orderService.product;

import java.math.BigDecimal;

public record ProductDto(
        Long productId,
        String title,
        String imageUrl,
        String sku,
        BigDecimal price,
        Integer quantity,
        Long categoryId
) {}
