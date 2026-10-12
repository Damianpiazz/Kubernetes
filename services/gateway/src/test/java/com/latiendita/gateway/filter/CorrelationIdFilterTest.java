package com.latiendita.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generaCorrelationIdCuandoNoVieneEnElRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String generado = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(generado).isNotBlank();
        assertThat(request.getAttribute(CorrelationIdFilter.MDC_KEY)).isEqualTo(generado);
    }

    @Test
    void propagaElCorrelationIdExistente() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader(CorrelationIdFilter.HEADER, "corr-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("corr-123");
    }

    @Test
    void inyectaElCorrelationIdEnElRequestHaciaDownstream() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader(CorrelationIdFilter.HEADER, "corr-xyz");

        final String[] seenDownstream = new String[1];
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req,
                                 jakarta.servlet.ServletResponse res) throws IOException, jakarta.servlet.ServletException {
                seenDownstream[0] = ((jakarta.servlet.http.HttpServletRequest) req)
                        .getHeader(CorrelationIdFilter.HEADER);
                super.doFilter(req, res);
            }
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seenDownstream[0]).isEqualTo("corr-xyz");
    }
}
