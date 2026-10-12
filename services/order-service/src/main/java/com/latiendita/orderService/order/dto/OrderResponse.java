package com.latiendita.orderService.order.dto;

import com.latiendita.orderService.order.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        String orderNumber,
        Long customerId,
        OrderStatus status,
        BigDecimal total,
        boolean refundRequired,
        String cancelReason,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt
) {}
