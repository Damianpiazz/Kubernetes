package com.latiendita.productService.product.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record ProductDto(
        Long productId,
        @Size(max = 255) String title,
        @Size(max = 500) String imageUrl,
        @NotBlank @Size(max = 100) String sku,
        @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal price,
        @Min(0) Integer quantity,
        Long categoryId
) {}
