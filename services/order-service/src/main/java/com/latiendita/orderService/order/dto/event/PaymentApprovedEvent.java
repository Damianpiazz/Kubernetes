package com.latiendita.orderService.order.dto.event;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentApprovedEvent(
        String type,
        Long paymentId,
        Long orderId,
        String orderNumber,
        Long customerId,
        BigDecimal amount,
        String currency,
        String provider,
        String providerPaymentId,
        Instant occurredAt) {}
