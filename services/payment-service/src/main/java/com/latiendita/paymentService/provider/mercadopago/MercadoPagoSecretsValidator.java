package com.latiendita.paymentService.provider.mercadopago;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"k8s", "prod"})
@Slf4j
public class MercadoPagoSecretsValidator {

    public MercadoPagoSecretsValidator(MercadoPagoProperties properties) {
        if (isBlank(properties.accessToken())) {
            throw new IllegalStateException(
                    "MERCADO_PAGO_ACCESS_TOKEN es obligatorio en el perfil k8s");
        }
        if (isBlank(properties.webhookSecret())) {
            throw new IllegalStateException(
                    "MERCADO_PAGO_WEBHOOK_SECRET es obligatorio en el perfil k8s");
        }
        log.info("Secretos de Mercado Pago presentes");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
