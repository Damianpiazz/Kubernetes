package com.latiendita.paymentService.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@Configuration
@EnableConfigurationProperties(ResilienceProperties.class)
public class ResilienceConfig {

    @Bean
    CircuitBreaker orderCircuitBreaker(ResilienceProperties properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(properties.failureRateThreshold())
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(properties.minimumNumberOfCalls())
                .waitDurationInOpenState(properties.waitDurationInOpenState())
                .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build();
        return CircuitBreaker.of("orderService", config);
    }

    @Bean
    Retry orderRetry(ResilienceProperties properties) {
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(properties.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        properties.waitDuration(), properties.waitDurationMultiplier()))
                .retryExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build();
        return Retry.of("orderService", config);
    }
}
