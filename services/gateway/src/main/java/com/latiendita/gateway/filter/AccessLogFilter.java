package com.latiendita.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("GATEWAY_ACCESS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - start;
            String correlationId = (String) request.getAttribute(CorrelationIdFilter.MDC_KEY);
            String userId = currentUserId();
            String ip = request.getRemoteAddr();
            String path = request.getRequestURI();

            log.info("method={} path={} status={} durationMs={} ip={} userId={} correlationId={}",
                    request.getMethod(), path, response.getStatus(), duration, ip,
                    userId != null ? userId : "-", correlationId);
            if (response.getStatus() >= 500) {
                log.warn("Upstream error: method={} path={} status={} durationMs={} correlationId={}",
                        request.getMethod(), path, response.getStatus(), duration, correlationId);
            }
        }
    }

    private String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return null;
    }
}
