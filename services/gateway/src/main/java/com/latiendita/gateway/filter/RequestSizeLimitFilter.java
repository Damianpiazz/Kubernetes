package com.latiendita.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxRequestSize;

    public RequestSizeLimitFilter(
            @Value("${gateway.limits.max-request-size:1048576}") long maxRequestSize) {
        this.maxRequestSize = maxRequestSize;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getContentLengthLong() > maxRequestSize) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(String.format(
                    "{\"type\":\"about:blank\",\"title\":\"Request demasiado grande\",\"status\":413,"
                            + "\"detail\":\"El cuerpo de la petición supera el máximo permitido de %d bytes\"}",
                    maxRequestSize));
            return;
        }
        filterChain.doFilter(request, response);
    }
}
