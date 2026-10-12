package com.latiendita.gateway.filter;

import com.latiendita.gateway.ratelimit.RateLimitConfig;
import com.latiendita.gateway.ratelimit.RateLimitProperties;
import com.latiendita.gateway.security.IdentityPropagationFilter;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 50)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private final RateLimiterRegistry registry;
    private final RateLimitProperties properties;

    public RateLimitFilter(RateLimiterRegistry registry, RateLimitProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (isExcluded(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String routeId = resolveRouteId(path);
        RateLimiter limiter = limiterFor(routeId);
        String key = resolveKey(request);

        if (!limiter.acquirePermission(1)) {
            log.warn("Rate limit excedido: route={} key={}", routeId, key);
            writeTooManyRequests(response, limiter);
            return;
        }

        response.setHeader("X-RateLimit-Limit",
                String.valueOf(limiter.getRateLimiterConfig().getLimitForPeriod()));
        response.setHeader("X-RateLimit-Remaining",
                String.valueOf(Math.max(0, limiter.getMetrics().getAvailablePermissions() - 1)));
        filterChain.doFilter(request, response);
    }

    private boolean isExcluded(String path) {
        return properties.excludedPaths().stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private String resolveRouteId(String path) {
        if (path.startsWith("/api/auth/")) {
            return "auth";
        }
        if (path.startsWith("/api/users/")) {
            return "users";
        }
        if (path.startsWith("/api/products/") || path.startsWith("/api/categories/")) {
            return "products";
        }
        if (path.startsWith("/api/orders/")) {
            return "orders";
        }
        if (path.startsWith("/api/payments/")) {
            return "payments";
        }
        if (path.startsWith("/api/webhooks/")) {
            return "webhooks";
        }
        return "unknown";
    }

    private RateLimiter limiterFor(String routeId) {
        if (properties.routes().containsKey(routeId)) {
            return registry.rateLimiter(RateLimitConfig.limiterNameForRoute(routeId));
        }
        return registry.rateLimiter(RateLimitConfig.DEFAULT_LIMITER);
    }

    private String resolveKey(HttpServletRequest request) {
        String userId = request.getHeader(IdentityPropagationFilter.USER_HEADER);
        if (userId != null && !userId.isBlank()) {
            return "user:" + userId;
        }
        return "ip:" + request.getRemoteAddr();
    }

    private void writeTooManyRequests(HttpServletResponse response, RateLimiter limiter) throws IOException {
        long retryAfterSeconds = limiter.getRateLimiterConfig().getLimitRefreshPeriod().toSeconds();
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(Math.max(1, retryAfterSeconds)));
        response.setHeader("X-RateLimit-Limit",
                String.valueOf(limiter.getRateLimiterConfig().getLimitForPeriod()));
        response.setHeader("X-RateLimit-Remaining", "0");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Demasiadas peticiones\","
                + "\"status\":429,\"detail\":\"Límite de peticiones excedido; reintente más tarde\"}");
    }
}
