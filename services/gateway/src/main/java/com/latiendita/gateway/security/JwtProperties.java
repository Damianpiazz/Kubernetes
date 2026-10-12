package com.latiendita.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("jwt")
public record JwtProperties(String secret, String issuer) {
}
