package com.latiendita.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jwt")
public record JwtProperties(String secret, String issuer) {

    public JwtProperties {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("jwt.secret debe tener al menos 32 caracteres (HS256)");
        }
    }
}
