package com.latiendita.orderService.product;

import com.latiendita.orderService.exception.ProductNotFoundException;
import com.latiendita.orderService.exception.ProductServiceUnavailableException;
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

    public ProductDto findById(Long productId) {
        try {
            return restClient.get()
                    .uri("/api/products/{productId}", productId)
                    .retrieve()
                    .body(ProductDto.class);
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
