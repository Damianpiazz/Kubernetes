package com.latiendita.gateway.ratelimit;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gateway.ratelimit")
public record RateLimitProperties(
        int defaultCapacity,
        int defaultRefillTokens,
        Duration defaultRefillInterval,
        Map<String, RouteLimit> routes,
        List<String> excludedPaths) {

    public RateLimitProperties {
        routes = routes != null ? routes : new HashMap<>();
        excludedPaths = excludedPaths != null ? excludedPaths : List.of();
    }

    public record RouteLimit(int capacity, int refillTokens, Duration refillInterval) {
    }
}
