package com.latiendita.paymentService.order;

import java.math.BigDecimal;
import java.util.List;

public record OrderDto(
        Long id,
        String orderNumber,
        Long customerId,
        String status,
        BigDecimal total,
        List<Item> items) {

    public record Item(
            Long productId,
            String productTitle,
            String sku,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal subtotal) {}
}
