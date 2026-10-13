package com.latiendita.paymentService.order;

import com.latiendita.paymentService.CurrentUser;
import com.latiendita.paymentService.exception.OrderNotFoundException;
import com.latiendita.paymentService.exception.PaymentGatewayUnavailableException;
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
public class OrderClient {

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public OrderDto findById(Long orderId) {
        Long customerId = CurrentUser.id();
        Supplier<OrderDto> supplier = () -> restClient.get()
                .uri("/api/orders/{orderId}", orderId)
                .header(CurrentUser.HEADER, String.valueOf(customerId))
                .retrieve()
                .body(OrderDto.class);
        try {
            return circuitBreaker.executeSupplier(() -> retry.executeSupplier(supplier));
        } catch (CallNotPermittedException ex) {
            log.warn("circuito abierto contra order-service; pedido {}", orderId);
            throw new PaymentGatewayUnavailableException(
                    "El servicio de pedidos no está disponible", ex);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                throw new OrderNotFoundException(orderId);
            }
            log.warn("order-service respondió {} para el pedido {}: {}",
                    ex.getStatusCode().value(), orderId, ex.getMessage());
            throw new PaymentGatewayUnavailableException(
                    "El servicio de pedidos no está disponible", ex);
        } catch (RestClientException ex) {
            log.warn("order-service no disponible: {}", ex.getMessage());
            throw new PaymentGatewayUnavailableException(
                    "El servicio de pedidos no está disponible", ex);
        }
    }
}
