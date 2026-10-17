package com.latiendita.productService.seed;

import java.math.BigDecimal;

public record SeedProduct(
        String title,
        String imageUrl,
        String sku,
        BigDecimal price,
        Integer quantity,
        String category
) {}
