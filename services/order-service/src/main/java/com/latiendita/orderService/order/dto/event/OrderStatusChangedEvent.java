package com.latiendita.orderService.order.dto.event;

import com.latiendita.orderService.order.OrderStatus;
import java.time.Instant;

public record OrderStatusChangedEvent(
        Long orderId,
        String orderNumber,
        Long customerId,
        OrderStatus from,
        OrderStatus to,
        Instant occurredAt
) {}
