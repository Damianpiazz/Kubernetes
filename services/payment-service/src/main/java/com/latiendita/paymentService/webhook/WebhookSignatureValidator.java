package com.latiendita.paymentService.webhook;

import com.latiendita.paymentService.provider.mercadopago.MercadoPagoProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookSignatureValidator {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final MercadoPagoProperties properties;

    public boolean isValid(String signatureHeader, String requestId, String resourceId) {
        String secret = properties.webhookSecret();
        if (secret == null || secret.isBlank()) {
            log.warn("MERCADO_PAGO_WEBHOOK_SECRET no está configurado; se descarta la notificación");
            return false;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("Webhook sin header x-signature");
            return false;
        }

        Map<String, String> parts = parse(signatureHeader);
        String ts = parts.get("ts");
        String v1 = parts.get("v1");
        if (ts == null || v1 == null) {
            log.warn("Header x-signature malformado: {}", signatureHeader);
            return false;
        }

        String template = "id:" + nullToEmpty(resourceId)
                + ";request-id:" + nullToEmpty(requestId)
                + ";ts:" + ts + ";";

        String computed = hmacSha256Hex(secret, template);

        boolean valid = MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                v1.getBytes(StandardCharsets.UTF_8));

        if (!valid) {
            log.warn("Firma inválida. template={} calculado={}", template, computed);
        }
        return valid;
    }

    private static Map<String, String> parse(String header) {
        Map<String, String> parts = new LinkedHashMap<>();
        for (String segment : header.split(",")) {
            String[] pair = segment.split("=", 2);
            if (pair.length == 2) {
                parts.put(pair[0].trim(), pair[1].trim());
            }
        }
        return parts;
    }

    private static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo calcular la firma HMAC", ex);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
