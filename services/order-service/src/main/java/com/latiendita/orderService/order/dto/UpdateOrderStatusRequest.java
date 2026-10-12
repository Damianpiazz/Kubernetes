package com.latiendita.orderService.order.dto;

import com.latiendita.orderService.order.OrderStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateOrderStatusRequest(
        @NotNull OrderStatus status
) {}
