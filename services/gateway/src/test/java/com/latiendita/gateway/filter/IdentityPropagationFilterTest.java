package com.latiendita.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.latiendita.gateway.security.IdentityPropagationFilter;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.authentication.TestingAuthenticationToken;

class IdentityPropagationFilterTest {

    private final IdentityPropagationFilter filter = new IdentityPropagationFilter();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String sub) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256")
                .subject(sub).issuer("auth-service").build();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(jwt, null, java.util.List.of()));
    }

    @Test
    void inyectaElSubDelJwtComoXUserId() throws Exception {
        authenticate("42");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        final String[] seen = new String[1];

        filter.doFilter(request, new MockHttpServletResponse(), capturing(seen));

        assertThat(seen[0]).isEqualTo("42");
    }

    @Test
    void descartaElXUserIdSpoofeadoDelCliente() throws Exception {
        authenticate("42");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.addHeader(IdentityPropagationFilter.USER_HEADER, "999");
        final String[] seen = new String[1];

        filter.doFilter(request, new MockHttpServletResponse(), capturing(seen));

        assertThat(seen[0]).isEqualTo("42");
    }

    @Test
    void sinPrincipalNoSeteaElHeader() throws Exception {

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        request.addHeader(IdentityPropagationFilter.USER_HEADER, "777");
        final String[] seen = new String[1];

        filter.doFilter(request, new MockHttpServletResponse(), capturing(seen));

        assertThat(seen[0]).isNull();
    }

    private MockFilterChain capturing(String[] seen) {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req,
                                 jakarta.servlet.ServletResponse res) throws IOException, jakarta.servlet.ServletException {
                seen[0] = ((jakarta.servlet.http.HttpServletRequest) req)
                        .getHeader(IdentityPropagationFilter.USER_HEADER);
                super.doFilter(req, res);
            }
        };
    }
}
