package com.latiendita.orderService.resilience;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("resilience")
public record ResilienceProperties(
        int maxAttempts,
        Duration waitDuration,
        double waitDurationMultiplier,
        float failureRateThreshold,
        int slidingWindowSize,
        int minimumNumberOfCalls,
        Duration waitDurationInOpenState) {
}
