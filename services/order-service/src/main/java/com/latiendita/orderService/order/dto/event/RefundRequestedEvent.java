package com.latiendita.orderService.order.dto.event;

import java.math.BigDecimal;
import java.time.Instant;

public record RefundRequestedEvent(
        Long orderId,
        String orderNumber,
        Long customerId,
        BigDecimal amount,
        Instant occurredAt
) {}
