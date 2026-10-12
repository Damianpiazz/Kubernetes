package com.latiendita.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class IdentityPropagationFilter extends OncePerRequestFilter {

    public static final String USER_HEADER = "X-User-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String sub = currentSub();

        filterChain.doFilter(new UserIdRequestWrapper(request, sub), response);
    }

    private String currentSub() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return null;
    }

    private static final class UserIdRequestWrapper extends HttpServletRequestWrapper {

        private final String userId;

        private UserIdRequestWrapper(HttpServletRequest request, String userId) {
            super(request);
            this.userId = userId;
        }

        @Override
        public String getHeader(String name) {
            if (USER_HEADER.equalsIgnoreCase(name)) {
                return userId;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (USER_HEADER.equalsIgnoreCase(name)) {
                if (userId == null) {
                    return Collections.emptyEnumeration();
                }
                return Collections.enumeration(Collections.singletonList(userId));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>();
            Enumeration<String> original = super.getHeaderNames();
            while (original.hasMoreElements()) {
                String name = original.nextElement();

                if (!USER_HEADER.equalsIgnoreCase(name)) {
                    names.add(name);
                }
            }
            if (userId != null) {
                names.add(USER_HEADER);
            }
            return Collections.enumeration(names);
        }
    }
}
