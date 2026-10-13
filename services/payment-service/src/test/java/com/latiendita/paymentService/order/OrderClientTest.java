package com.latiendita.paymentService.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.latiendita.paymentService.exception.OrderNotFoundException;
import com.latiendita.paymentService.exception.PaymentGatewayUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class OrderClientTest {

    private static final String URL = "http://order-service/api/orders/7";

    private static final String ORDER_JSON = """
            {"id":7,"orderNumber":"ORD-7","customerId":1,"status":"PENDING","total":2049.48,
             "items":[{"productId":42,"productTitle":"Notebook Lenovo","sku":"NB-LEN-001",
                       "quantity":2,"unitPrice":999.99,"subtotal":1999.98}]}
            """;

    private MockRestServiceServer server;
    private CircuitBreaker circuitBreaker;
    private OrderClient client;

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        RestClient.Builder builder = RestClient.builder().baseUrl("http://order-service");
        server = MockRestServiceServer.bindTo(builder).build();
        circuitBreaker = CircuitBreaker.of("orderService", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build());
        Retry retry = Retry.of("orderService", RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(attempt -> 1L)
                .retryExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build());
        client = new OrderClient(builder.build(), circuitBreaker, retry);
    }

    @AfterEach
    void tearDown() {
        server.reset();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldReturnOrderOnSuccess() {
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess(ORDER_JSON, MediaType.APPLICATION_JSON));

        OrderDto dto = client.findById(7L);

        assertThat(dto.id()).isEqualTo(7L);
        assertThat(dto.status()).isEqualTo("PENDING");
        assertThat(dto.total()).isEqualByComparingTo("2049.48");
        server.verify();
    }

    @Test
    void shouldThrowNotFoundWithoutRetryOn404() {
        server.expect(once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.findById(7L))
                .isInstanceOf(OrderNotFoundException.class);

        server.verify();
    }

    @Test
    void shouldRetryOn5xxAndThrowUnavailableAfterExhaustingAttempts() {
        server.expect(times(3), requestTo(URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.findById(7L))
                .isInstanceOf(PaymentGatewayUnavailableException.class);

        server.verify();
    }

    @Test
    void shouldRecoverWhenRetrySucceeds() {
        server.expect(once(), requestTo(URL))
                .andRespond(withServerError());
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess(ORDER_JSON, MediaType.APPLICATION_JSON));

        OrderDto dto = client.findById(7L);

        assertThat(dto.id()).isEqualTo(7L);
        server.verify();
    }

    @Test
    void shouldThrowUnavailableWhenCircuitIsOpen() {
        circuitBreaker.transitionToOpenState();

        assertThatThrownBy(() -> client.findById(7L))
                .isInstanceOf(PaymentGatewayUnavailableException.class)
                .hasRootCauseInstanceOf(
                        io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);

        server.verify();
    }
}
