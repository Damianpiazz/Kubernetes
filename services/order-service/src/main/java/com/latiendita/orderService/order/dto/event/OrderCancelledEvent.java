package com.latiendita.orderService.order.dto.event;

import java.time.Instant;

public record OrderCancelledEvent(
        Long orderId,
        String orderNumber,
        Long customerId,
        String reason,
        boolean refundRequired,
        Instant occurredAt
) {}
