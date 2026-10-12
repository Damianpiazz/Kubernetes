package com.latiendita.gateway.ratelimit;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    public static final String DEFAULT_LIMITER = "default";

    @Bean
    RateLimiterRegistry rateLimiterRegistry(RateLimitProperties properties) {
        RateLimiterConfig defaultConfig = RateLimiterConfig.custom()
                .limitForPeriod(properties.defaultCapacity())
                .limitRefreshPeriod(properties.defaultRefillInterval())
                .timeoutDuration(Duration.ZERO)
                .build();

        RateLimiterRegistry registry = RateLimiterRegistry.of(defaultConfig);

        for (Map.Entry<String, RateLimitProperties.RouteLimit> entry : properties.routes().entrySet()) {
            RateLimitProperties.RouteLimit limit = entry.getValue();
            RateLimiterConfig config = RateLimiterConfig.custom()
                    .limitForPeriod(limit.capacity())
                    .limitRefreshPeriod(limit.refillInterval())
                    .timeoutDuration(Duration.ZERO)
                    .build();
            registry.rateLimiter(limiterNameForRoute(entry.getKey()), config);
        }
        return registry;
    }

    public static String limiterNameForRoute(String routeId) {
        return "route:" + routeId;
    }
}
