package com.latiendita.paymentService.provider.mercadopago;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mercado-pago")
public record MercadoPagoProperties(
        String accessToken,
        String webhookSecret,
        String notificationUrl,
        String backUrlSuccess,
        String backUrlFailure,
        String backUrlPending,
        String statementDescriptor) {}
