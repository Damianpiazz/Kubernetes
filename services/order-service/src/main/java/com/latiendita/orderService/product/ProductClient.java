package com.latiendita.orderService.product;

import com.latiendita.orderService.exception.ProductNotFoundException;
import com.latiendita.orderService.exception.ProductServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductClient {

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public ProductDto findById(Long productId) {
        Supplier<ProductDto> supplier = () -> restClient.get()
                .uri("/api/products/{productId}", productId)
                .retrieve()
                .body(ProductDto.class);
        try {
            return circuitBreaker.executeSupplier(() -> retry.executeSupplier(supplier));
        } catch (CallNotPermittedException ex) {
            log.warn("circuito abierto contra product-service; producto {}", productId);
            throw new ProductServiceUnavailableException(ex);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new ProductNotFoundException(productId);
            }
            log.warn("product-service respondió {} para el producto {}: {}",
                    ex.getStatusCode().value(), productId, ex.getMessage());
            throw new ProductServiceUnavailableException(ex);
        } catch (RestClientException ex) {
            log.warn("product-service no disponible: {}", ex.getMessage());
            throw new ProductServiceUnavailableException(ex);
        }
    }
}
