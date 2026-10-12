package com.latiendita.gateway.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gateway.cors")
public record CorsProperties(List<String> allowedOrigins, List<String> allowedMethods,
                             List<String> allowedHeaders, boolean allowCredentials,
                             Duration maxAge) {
}
