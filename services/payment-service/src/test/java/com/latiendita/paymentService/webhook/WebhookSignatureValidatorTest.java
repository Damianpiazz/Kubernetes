package com.latiendita.paymentService.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.latiendita.paymentService.provider.mercadopago.MercadoPagoProperties;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class WebhookSignatureValidatorTest {

    private static final String SECRET = "test-webhook-secret-1234567890";
    private static final String RESOURCE_ID = "1234567890";
    private static final String REQUEST_ID = "req-abc-123";
    private static final String TS = "1704908010";

    private WebhookSignatureValidator validator(String secret) {
        return new WebhookSignatureValidator(new MercadoPagoProperties(
                "access-token", secret, "https://api.tienda.com/webhook",
                "https://tienda.com/success", "https://tienda.com/failure",
                "https://tienda.com/pending", "La Tiendita"));
    }

    @Test
    void shouldAcceptValidSignature() {
        String v1 = hmacHex(SECRET, template(RESOURCE_ID, REQUEST_ID, TS));
        String header = "ts=" + TS + ",v1=" + v1;

        assertThat(validator(SECRET).isValid(header, REQUEST_ID, RESOURCE_ID)).isTrue();
    }

    @Test
    void shouldRejectSignatureComputedWithAnotherSecret() {
        String v1 = hmacHex("otro-secreto-diferente", template(RESOURCE_ID, REQUEST_ID, TS));
        String header = "ts=" + TS + ",v1=" + v1;

        assertThat(validator(SECRET).isValid(header, REQUEST_ID, RESOURCE_ID)).isFalse();
    }

    @Test
    void shouldRejectTamperedResourceId() {
        String v1 = hmacHex(SECRET, template(RESOURCE_ID, REQUEST_ID, TS));
        String header = "ts=" + TS + ",v1=" + v1;

        assertThat(validator(SECRET).isValid(header, REQUEST_ID, "9999999999")).isFalse();
    }

    @Test
    void shouldRejectTamperedTimestamp() {
        String v1 = hmacHex(SECRET, template(RESOURCE_ID, REQUEST_ID, TS));
        String header = "ts=1704908011,v1=" + v1;

        assertThat(validator(SECRET).isValid(header, REQUEST_ID, RESOURCE_ID)).isFalse();
    }

    @Test
    void shouldRejectMalformedHeader() {
        assertThat(validator(SECRET).isValid("garbage", REQUEST_ID, RESOURCE_ID)).isFalse();
        assertThat(validator(SECRET).isValid("ts=123", REQUEST_ID, RESOURCE_ID)).isFalse();
        assertThat(validator(SECRET).isValid("v1=abc", REQUEST_ID, RESOURCE_ID)).isFalse();
    }

    @Test
    void shouldRejectMissingHeader() {
        assertThat(validator(SECRET).isValid(null, REQUEST_ID, RESOURCE_ID)).isFalse();
        assertThat(validator(SECRET).isValid("", REQUEST_ID, RESOURCE_ID)).isFalse();
    }

    @Test
    void shouldRejectWhenSecretIsNotConfigured() {
        String v1 = hmacHex(SECRET, template(RESOURCE_ID, REQUEST_ID, TS));
        String header = "ts=" + TS + ",v1=" + v1;

        assertThat(validator("").isValid(header, REQUEST_ID, RESOURCE_ID)).isFalse();
        assertThat(validator(null).isValid(header, REQUEST_ID, RESOURCE_ID)).isFalse();
    }

    @Test
    void shouldRejectWhenRequestIdDoesNotMatch() {
        String v1 = hmacHex(SECRET, template(RESOURCE_ID, "otro-request-id", TS));
        String header = "ts=" + TS + ",v1=" + v1;

        assertThat(validator(SECRET).isValid(header, REQUEST_ID, RESOURCE_ID)).isFalse();
    }

    @Test
    void shouldHandleNullResourceIdWithoutFailing() {
        String v1 = hmacHex(SECRET, templateWithoutId(REQUEST_ID, TS));
        String header = "ts=" + TS + ",v1=" + v1;

        assertThat(validator(SECRET).isValid(header, REQUEST_ID, null)).isTrue();
    }

    /**
     * Mercado Pago pide remover el segmento entero cuando el valor no está, no
     * dejarlo vacío: si el manifest quedara "id:;request-id:...;ts:...;" el HMAC
     * no coincidiría con el que calculó Mercado Pago.
     */
    @Test
    void shouldRemoveIdSegmentWhenResourceIdIsBlank() {
        String v1 = hmacHex(SECRET, templateWithoutId(REQUEST_ID, TS));
        String header = "ts=" + TS + ",v1=" + v1;

        assertThat(validator(SECRET).isValid(header, REQUEST_ID, "")).isTrue();
        assertThat(validator(SECRET).isValid(header, REQUEST_ID, "  ")).isTrue();
    }

    @Test
    void shouldRejectEmptyIdSegmentWhenSignatureWasComputedWithIdRemoved() {
        String v1 = hmacHex(SECRET, "id:;request-id:" + REQUEST_ID + ";ts:" + TS + ";");
        String header = "ts=" + TS + ",v1=" + v1;

        assertThat(validator(SECRET).isValid(header, REQUEST_ID, RESOURCE_ID)).isFalse();
    }

    private static String template(String resourceId, String requestId, String ts) {
        return "id:" + resourceId + ";request-id:" + requestId + ";ts:" + ts + ";";
    }

    private static String templateWithoutId(String requestId, String ts) {
        return "request-id:" + requestId + ";ts:" + ts + ";";
    }

    private static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
