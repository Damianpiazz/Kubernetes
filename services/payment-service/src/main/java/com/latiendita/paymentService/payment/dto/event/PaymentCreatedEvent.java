package com.latiendita.paymentService.payment.dto.event;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentCreatedEvent(
        String type,
        Long paymentId,
        Long orderId,
        String orderNumber,
        Long customerId,
        BigDecimal amount,
        String currency,
        Instant occurredAt) {}
