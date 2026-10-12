package com.latiendita.gateway;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class TestJwtFactory {

    public static final String TEST_SECRET = "test-secret-32-chars-minimum-abcdef";
    public static final String TEST_ISSUER = "auth-service";

    private TestJwtFactory() {
    }

    public static String token(long accountId) {
        try {
            String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            long now = System.currentTimeMillis() / 1000;
            String claims = base64Url("{\"iss\":\"" + TEST_ISSUER + "\","
                    + "\"sub\":\"" + accountId + "\","
                    + "\"email\":\"ana@mail.com\","
                    + "\"username\":\"ana\","
                    + "\"iat\":" + now + ","
                    + "\"exp\":" + (now + 900) + "}");

            String signingInput = header + "." + claims;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = base64Url(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));

            return signingInput + "." + signature;
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo generar el token de prueba", ex);
        }
    }

    public static String expiredToken(long accountId) {
        try {
            String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            long now = System.currentTimeMillis() / 1000;
            String claims = base64Url("{\"iss\":\"" + TEST_ISSUER + "\","
                    + "\"sub\":\"" + accountId + "\","
                    + "\"iat\":" + (now - 1800) + ","
                    + "\"exp\":" + (now - 900) + "}");
            String signingInput = header + "." + claims;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signature = base64Url(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + signature;
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo generar el token de prueba", ex);
        }
    }

    private static String base64Url(byte[] bytes) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String base64Url(String value) {
        return base64Url(value.getBytes(StandardCharsets.UTF_8));
    }

    public static Map<String, Object> testProperties() {
        return Map.of(
                "eureka.client.enabled", "false",
                "jwt.secret", TEST_SECRET,
                "jwt.issuer", TEST_ISSUER);
    }
}
