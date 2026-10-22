package com.latiendita.paymentService.provider.mercadopago;

import com.latiendita.paymentService.exception.PaymentGatewayUnavailableException;
import com.latiendita.paymentService.provider.PaymentGateway;
import com.latiendita.paymentService.provider.ProviderType;
import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.client.preference.PreferenceBackUrlsRequest;
import com.mercadopago.client.preference.PreferenceClient;
import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferencePayerRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MercadoPagoAdapter implements PaymentGateway {

    private final MercadoPagoProperties properties;
    private final MercadoPagoStatusMapper statusMapper;

    @Override
    public ProviderType type() {
        return ProviderType.MERCADO_PAGO;
    }

    @Override
    public GatewayPreference createPreference(GatewayPreferenceRequest request) {
        requireAccessToken();
        log.info("Creando preferencia de Mercado Pago para la referencia {}",
                request.externalReference());

        try {
            List<PreferenceItemRequest> items = request.items().stream()
                    .map(item -> PreferenceItemRequest.builder()
                            .id(item.id())
                            .title(item.title())
                            .description(item.description())
                            .quantity(item.quantity())
                            .unitPrice(item.unitPrice())
                            .build())
                    .collect(Collectors.toList());

            PreferenceRequest preferenceRequest = PreferenceRequest.builder()
                    .items(items)
                    .payer(PreferencePayerRequest.builder()
                            .email(request.payerEmail())
                            .build())
                    .backUrls(PreferenceBackUrlsRequest.builder()
                            .success(request.backUrlSuccess())
                            .failure(request.backUrlFailure())
                            .pending(request.backUrlPending())
                            .build())
                    .autoReturn("approved")
                    .externalReference(request.externalReference())
                    // Aparece en el resumen de la tarjeta del titular: baja las
                    // desconocimientos/chargebacks. Sólo se envía si está configurado.
                    .statementDescriptor(nullToNull(request.statementDescriptor()))
                    .notificationUrl(request.notificationUrl())
                    .build();

            com.mercadopago.resources.preference.Preference preference =
                    new PreferenceClient().create(preferenceRequest);

            log.info("Preferencia creada. id={} initPoint={}",
                    preference.getId(), preference.getInitPoint());

            return new GatewayPreference(preference.getId(), preference.getInitPoint());

        } catch (MPApiException ex) {
            log.error("Error de la API de Mercado Pago creando la preferencia {}: status={} body={}",
                    request.externalReference(), ex.getStatusCode(),
                    ex.getApiResponse() == null ? null : ex.getApiResponse().getContent());
            throw new PaymentGatewayUnavailableException(
                    "El proveedor de pagos respondió " + ex.getStatusCode(), ex);
        } catch (MPException ex) {
            log.error("Error del SDK de Mercado Pago creando la preferencia {}: {}",
                    request.externalReference(), ex.getMessage());
            throw new PaymentGatewayUnavailableException(ex);
        }
    }

    @Override
    public GatewayPayment getPayment(String providerPaymentId) {
        requireAccessToken();
        log.info("Consultando el pago {} en Mercado Pago", providerPaymentId);

        long numericId;
        try {
            numericId = Long.parseLong(providerPaymentId);
        } catch (NumberFormatException ex) {
            throw new PaymentGatewayUnavailableException(
                    "Identificador de pago inválido: " + providerPaymentId, ex);
        }

        try {
            com.mercadopago.resources.payment.Payment mpPayment =
                    new PaymentClient().get(numericId);

            return new GatewayPayment(
                    String.valueOf(mpPayment.getId()),
                    statusMapper.toDomain(mpPayment.getStatus()),
                    mpPayment.getStatusDetail(),
                    mpPayment.getPaymentMethodId(),
                    mpPayment.getPaymentTypeId(),
                    mpPayment.getTransactionAmount(),
                    mpPayment.getCurrencyId(),
                    mpPayment.getExternalReference());

        } catch (MPApiException ex) {
            log.error("Error de la API de Mercado Pago consultando el pago {}: status={}",
                    providerPaymentId, ex.getStatusCode());
            throw new PaymentGatewayUnavailableException(
                    "El proveedor de pagos respondió " + ex.getStatusCode(), ex);
        } catch (MPException ex) {
            log.error("Error del SDK de Mercado Pago consultando el pago {}: {}",
                    providerPaymentId, ex.getMessage());
            throw new PaymentGatewayUnavailableException(ex);
        }
    }

    private void requireAccessToken() {
        if (properties.accessToken() == null || properties.accessToken().isBlank()) {
            throw new PaymentGatewayUnavailableException(
                    "MERCADO_PAGO_ACCESS_TOKEN no está configurado");
        }
        MercadoPagoConfig.setAccessToken(properties.accessToken());
    }

    /** Un campo vacío debe ir como {@code null}: Mercado Pago valida el string vacío. */
    private static String nullToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
