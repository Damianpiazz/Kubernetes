package com.latiendita.paymentService.provider;

import com.latiendita.paymentService.exception.InvalidPaymentException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentGatewayFactory {

    private final List<PaymentGateway> gateways;

    public PaymentGateway forProvider(ProviderType type) {
        return gateways.stream()
                .filter(gateway -> gateway.type() == type)
                .findFirst()
                .orElseThrow(() -> new InvalidPaymentException(
                        "No hay un adaptador de pago registrado para " + type));
    }
}
