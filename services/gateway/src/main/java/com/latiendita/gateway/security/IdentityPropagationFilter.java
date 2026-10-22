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

/**
 * Propaga la identidad del JWT a los servicios internos.
 *
 * Los microservicios no validan el token (sólo lo hace el gateway), así que la
 * identidad viaja por header: `X-User-Id` con el `sub` y `X-User-Email` con el
 * claim `email`. El segundo es necesario en user-service, cuyo perfil se busca
 * por correo: su id de base de datos es distinto del id de la cuenta.
 *
 * Los headers se pisan SIEMPRE con los claims del token (aunque vengan en
 * null), de modo que un cliente no pueda suplantar su identidad mandándolos a
 * mano.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
public class IdentityPropagationFilter extends OncePerRequestFilter {

    public static final String USER_HEADER = "X-User-Id";
    public static final String EMAIL_HEADER = "X-User-Email";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(new IdentityRequestWrapper(request, currentIdentity()), response);
    }

    private Identity currentIdentity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return new Identity(jwt.getSubject(), jwt.getClaimAsString("email"));
        }
        return new Identity(null, null);
    }

    private record Identity(String sub, String email) {}

    private static final class IdentityRequestWrapper extends HttpServletRequestWrapper {

        private final Identity identity;

        private IdentityRequestWrapper(HttpServletRequest request, Identity identity) {
            super(request);
            this.identity = identity;
        }

        @Override
        public String getHeader(String name) {
            if (USER_HEADER.equalsIgnoreCase(name)) {
                return identity.sub();
            }
            if (EMAIL_HEADER.equalsIgnoreCase(name)) {
                return identity.email();
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String value = null;
            if (USER_HEADER.equalsIgnoreCase(name)) {
                value = identity.sub();
            } else if (EMAIL_HEADER.equalsIgnoreCase(name)) {
                value = identity.email();
            }

            if (value != null) {
                return Collections.enumeration(Collections.singletonList(value));
            }
            if (USER_HEADER.equalsIgnoreCase(name) || EMAIL_HEADER.equalsIgnoreCase(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>();
            Enumeration<String> original = super.getHeaderNames();
            while (original.hasMoreElements()) {
                String name = original.nextElement();

                if (!USER_HEADER.equalsIgnoreCase(name) && !EMAIL_HEADER.equalsIgnoreCase(name)) {
                    names.add(name);
                }
            }
            if (identity.sub() != null) {
                names.add(USER_HEADER);
            }
            if (identity.email() != null) {
                names.add(EMAIL_HEADER);
            }
            return Collections.enumeration(names);
        }
    }
}
