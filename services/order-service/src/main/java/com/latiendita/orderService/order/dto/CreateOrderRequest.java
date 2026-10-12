package com.latiendita.orderService.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.List;

public record CreateOrderRequest(
        @NotEmpty List<@Valid ItemRequest> items,
        @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal total
) {}
