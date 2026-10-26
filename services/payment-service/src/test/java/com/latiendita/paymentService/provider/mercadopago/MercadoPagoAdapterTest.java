package com.latiendita.paymentService.provider.mercadopago;

import static org.assertj.core.api.Assertions.assertThat;

import com.latiendita.paymentService.provider.PaymentGateway.GatewayPreferenceItem;
import com.latiendita.paymentService.provider.PaymentGateway.GatewayPreferenceRequest;
import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifica lo que viaja a la API de Mercado Pago al crear la preferencia de
 * Checkout Pro (sin llamada HTTP: sólo la construcción del request).
 */
class MercadoPagoAdapterTest {

    private static final String SUCCESS_URL = "https://tienda.com/checkout/success";

    private final MercadoPagoAdapter adapter = new MercadoPagoAdapter(
            new MercadoPagoProperties(
                    "APP_USR-test-token", "test-webhook-secret",
                    "https://tienda.com/api/webhooks/mercado-pago",
                    SUCCESS_URL,
                    "https://tienda.com/checkout/failure",
                    "https://tienda.com/checkout/pending",
                    "La Tiendita"),
            new MercadoPagoStatusMapper());

    @Test
    void shouldSendCurrencyIdOnEveryItem() {
        PreferenceRequest preference = adapter.buildPreferenceRequest(request("ARS", SUCCESS_URL));

        assertThat(preference.getItems())
                .hasSize(2)
                .allSatisfy(item -> assertThat(item.getCurrencyId()).isEqualTo("ARS"));
    }

    @Test
    void shouldOmitCurrencyIdWhenCurrencyIsBlank() {
        PreferenceRequest preference = adapter.buildPreferenceRequest(request("  ", SUCCESS_URL));

        assertThat(preference.getItems())
                .first()
                .extracting(PreferenceItemRequest::getCurrencyId)
                .isNull();
    }

    @Test
    void shouldSetAutoReturnOnlyWhenSuccessBackUrlIsPresent() {
        PreferenceRequest preference = adapter.buildPreferenceRequest(request("ARS", SUCCESS_URL));

        assertThat(preference.getAutoReturn()).isEqualTo("approved");
        assertThat(preference.getBackUrls().getSuccess()).isEqualTo(SUCCESS_URL);
    }

    @Test
    void shouldOmitAutoReturnWhenSuccessBackUrlIsBlank() {
        PreferenceRequest preference = adapter.buildPreferenceRequest(request("ARS", " "));

        // La API de MP responde 400 si auto_return viene sin back_urls.success.
        assertThat(preference.getAutoReturn()).isNull();
        assertThat(preference.getBackUrls().getSuccess()).isNull();
    }

    @Test
    void shouldMapItemFieldsAndExternalReference() {
        PreferenceRequest preference = adapter.buildPreferenceRequest(request("ARS", SUCCESS_URL));

        PreferenceItemRequest item = preference.getItems().get(0);
        assertThat(item.getId()).isEqualTo("7");
        assertThat(item.getTitle()).isEqualTo("Teclado mecánico");
        assertThat(item.getDescription()).isEqualTo("Teclado mecánico");
        assertThat(item.getQuantity()).isEqualTo(2);
        assertThat(item.getUnitPrice()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(preference.getExternalReference()).isEqualTo("PEDIDO-42");
        assertThat(preference.getPayer().getEmail()).isEqualTo("ana@mail.com");
        assertThat(preference.getStatementDescriptor()).isEqualTo("La Tiendita");
        assertThat(preference.getNotificationUrl())
                .isEqualTo("https://tienda.com/api/webhooks/mercado-pago");
    }

    @Test
    void shouldOmitBlankStatementDescriptorAndNotificationUrl() {
        GatewayPreferenceRequest withoutOptionals = new GatewayPreferenceRequest(
                "PEDIDO-42",
                new BigDecimal("3000.00"),
                "ARS",
                "ana@mail.com",
                List.of(new GatewayPreferenceItem("7", "Teclado mecánico",
                        "Teclado mecánico", 2, new BigDecimal("1500.00"))),
                "", "", "", "", "");

        PreferenceRequest preference = adapter.buildPreferenceRequest(withoutOptionals);

        assertThat(preference.getStatementDescriptor()).isNull();
        assertThat(preference.getNotificationUrl()).isNull();
    }

    private static GatewayPreferenceRequest request(String currency, String backUrlSuccess) {
        return new GatewayPreferenceRequest(
                "PEDIDO-42",
                new BigDecimal("3000.00"),
                currency,
                "ana@mail.com",
                List.of(
                        new GatewayPreferenceItem("7", "Teclado mecánico",
                                "Teclado mecánico", 2, new BigDecimal("1500.00")),
                        new GatewayPreferenceItem("8", "Mouse",
                                "Mouse inalámbrico", 1, new BigDecimal("500.00"))),
                "https://tienda.com/api/webhooks/mercado-pago",
                backUrlSuccess,
                "https://tienda.com/checkout/failure",
                "https://tienda.com/checkout/pending",
                "La Tiendita");
    }
}
