package com.latiendita.orderService.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.latiendita.orderService.exception.ProductNotFoundException;
import com.latiendita.orderService.exception.ProductServiceUnavailableException;
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
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class ProductClientTest {

    private static final String URL = "http://product-service/api/products/42";

    private static final String PRODUCT_JSON = """
            {"productId":42,"title":"Notebook Lenovo","imageUrl":"https://cdn/NB.png",
             "sku":"NB-LEN-001","price":999.99,"quantity":10,"categoryId":7}
            """;

    private MockRestServiceServer server;
    private CircuitBreaker circuitBreaker;
    private ProductClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://product-service");
        server = MockRestServiceServer.bindTo(builder).build();
        circuitBreaker = CircuitBreaker.of("productService", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .recordExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build());
        Retry retry = Retry.of("productService", RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(attempt -> 1L)
                .retryExceptions(ResourceAccessException.class, HttpServerErrorException.class)
                .build());
        client = new ProductClient(builder.build(), circuitBreaker, retry);
    }

    @AfterEach
    void tearDown() {
        server.reset();
    }

    @Test
    void shouldReturnProductOnSuccess() {
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess(PRODUCT_JSON, MediaType.APPLICATION_JSON));

        ProductDto dto = client.findById(42L);

        assertThat(dto.productId()).isEqualTo(42L);
        assertThat(dto.sku()).isEqualTo("NB-LEN-001");
        assertThat(dto.price()).isEqualByComparingTo("999.99");
        server.verify();
    }

    @Test
    void shouldThrowNotFoundWithoutRetryOn404() {
        server.expect(once(), requestTo(URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.findById(42L))
                .isInstanceOf(ProductNotFoundException.class);

        server.verify();
    }

    @Test
    void shouldRetryOn5xxAndThrowUnavailableAfterExhaustingAttempts() {
        server.expect(times(3), requestTo(URL))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.findById(42L))
                .isInstanceOf(ProductServiceUnavailableException.class);

        server.verify();
    }

    @Test
    void shouldRecoverWhenRetrySucceeds() {
        server.expect(once(), requestTo(URL))
                .andRespond(withServerError());
        server.expect(once(), requestTo(URL))
                .andRespond(withSuccess(PRODUCT_JSON, MediaType.APPLICATION_JSON));

        ProductDto dto = client.findById(42L);

        assertThat(dto.productId()).isEqualTo(42L);
        server.verify();
    }

    @Test
    void shouldThrowUnavailableWhenCircuitIsOpen() {
        circuitBreaker.transitionToOpenState();

        assertThatThrownBy(() -> client.findById(42L))
                .isInstanceOf(ProductServiceUnavailableException.class)
                .hasRootCauseInstanceOf(
                        io.github.resilience4j.circuitbreaker.CallNotPermittedException.class);

        server.verify();
    }
}
