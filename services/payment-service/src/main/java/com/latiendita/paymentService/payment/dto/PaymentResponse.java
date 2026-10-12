package com.latiendita.paymentService.payment.dto;

import com.latiendita.paymentService.payment.PaymentStatus;
import com.latiendita.paymentService.provider.ProviderType;
import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        Long id,
        Long orderId,
        String orderNumber,
        Long customerId,
        ProviderType provider,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        String preferenceId,
        String checkoutUrl,
        String paymentMethodId,
        String paymentType,
        String statusDetail,
        Instant paidAt,
        Instant createdAt,
        Instant updatedAt) {}
