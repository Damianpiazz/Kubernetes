package com.latiendita.orderService.order.dto;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long productId,
        String productTitle,
        String sku,
        Integer quantity,
        BigDecimal unitPrice,
        BigDecimal subtotal
) {}
