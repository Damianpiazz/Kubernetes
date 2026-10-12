package com.latiendita.paymentService.provider.mercadopago;

import com.latiendita.paymentService.payment.PaymentStatus;
import org.springframework.stereotype.Component;

@Component
public class MercadoPagoStatusMapper {

    public PaymentStatus toDomain(String mpStatus) {
        if (mpStatus == null || mpStatus.isBlank()) {
            return PaymentStatus.PENDING;
        }
        return switch (mpStatus.toLowerCase()) {
            case "approved" -> PaymentStatus.APPROVED;
            case "rejected" -> PaymentStatus.REJECTED;
            case "cancelled" -> PaymentStatus.CANCELLED;
            case "expired" -> PaymentStatus.EXPIRED;
            case "pending", "authorized", "in_process", "in_mediation" -> PaymentStatus.PENDING;
            default -> PaymentStatus.PENDING;
        };
    }
}
