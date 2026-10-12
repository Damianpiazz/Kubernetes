package com.latiendita.orderService.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ItemRequest(
        @NotNull Long productId,
        @Min(1) Integer quantity
) {}
