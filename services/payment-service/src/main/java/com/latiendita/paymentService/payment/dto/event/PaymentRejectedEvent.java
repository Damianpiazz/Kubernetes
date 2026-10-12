package com.latiendita.paymentService.payment.dto.event;

import java.time.Instant;

public record PaymentRejectedEvent(
        String type,
        Long paymentId,
        Long orderId,
        Long customerId,
        String statusDetail,
        Instant occurredAt) {}
