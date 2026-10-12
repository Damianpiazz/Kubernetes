package com.latiendita.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.latiendita.gateway.ratelimit.RateLimitConfig;
import com.latiendita.gateway.ratelimit.RateLimitProperties;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitFilterTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private RateLimiterRegistry registry;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        registry = RateLimiterRegistry.ofDefaults();

        registry.rateLimiter(RateLimitConfig.DEFAULT_LIMITER, RateLimiterConfig.custom()
                .limitForPeriod(2).limitRefreshPeriod(MINUTE).timeoutDuration(Duration.ZERO)
                .build());

        registry.rateLimiter(RateLimitConfig.limiterNameForRoute("auth"), RateLimiterConfig.custom()
                .limitForPeriod(10).limitRefreshPeriod(MINUTE).timeoutDuration(Duration.ZERO)
                .build());

        RateLimitProperties properties = new RateLimitProperties(2, 2, MINUTE,
                Map.of("auth", new RateLimitProperties.RouteLimit(10, 10, MINUTE)),
                List.of("/api/webhooks/**", "/actuator/**"));
        filter = new RateLimitFilter(registry, properties);
    }

    @Test
    void permitePeticionDentroDelLimite() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("2");
    }

    @Test
    void devuelve429AlExcederElLimite() throws Exception {
        RateLimiter limiter = registry.rateLimiter(RateLimitConfig.DEFAULT_LIMITER);
        limiter.acquirePermission(2);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotNull();
        assertThat(response.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    void noLimitaLasRutasExcluidas() throws Exception {
        RateLimiter limiter = registry.rateLimiter(RateLimitConfig.DEFAULT_LIMITER);
        limiter.acquirePermission(2);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/webhooks/mercado-pago");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void usaElLimiterDeLaRutaCuandoEstaConfigurado() throws Exception {
        registry.rateLimiter(RateLimitConfig.DEFAULT_LIMITER).acquirePermission(2);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("10");
    }

    @Test
    void usaLaIpComoClaveCuandoNoHayToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-RateLimit-Limit")).isEqualTo("2");
    }
}
