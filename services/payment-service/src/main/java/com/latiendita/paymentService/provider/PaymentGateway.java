package com.latiendita.paymentService.provider;

import com.latiendita.paymentService.payment.PaymentStatus;
import java.math.BigDecimal;
import java.util.List;

public interface PaymentGateway {

    ProviderType type();

    GatewayPreference createPreference(GatewayPreferenceRequest request);

    GatewayPayment getPayment(String providerPaymentId);

    record GatewayPreferenceItem(String id, String title, String description,
                                 Integer quantity, BigDecimal unitPrice) {}

    record GatewayPreferenceRequest(
            String externalReference,
            BigDecimal amount,
            String currency,
            String payerEmail,
            List<GatewayPreferenceItem> items,
            String notificationUrl,
            String backUrlSuccess,
            String backUrlFailure,
            String backUrlPending,
            String statementDescriptor) {}

    record GatewayPreference(String preferenceId, String checkoutUrl) {}

    record GatewayPayment(
            String providerPaymentId,
            PaymentStatus status,
            String statusDetail,
            String paymentMethodId,
            String paymentType,
            BigDecimal transactionAmount,
            String currency,
            String externalReference) {}
}
