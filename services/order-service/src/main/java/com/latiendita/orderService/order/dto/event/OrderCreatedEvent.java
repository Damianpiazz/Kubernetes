package com.latiendita.orderService.order.dto.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderCreatedEvent(
        Long orderId,
        String orderNumber,
        Long customerId,
        BigDecimal total,
        List<Item> items,
        Instant occurredAt
) {
    public record Item(Long productId, Integer quantity, BigDecimal unitPrice, BigDecimal subtotal) {}
}
