package com.latiendita.paymentService.order;

import com.latiendita.paymentService.CurrentUser;
import com.latiendita.paymentService.exception.OrderNotFoundException;
import com.latiendita.paymentService.exception.PaymentGatewayUnavailableException;
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

    public OrderDto findById(Long orderId) {
        Long customerId = CurrentUser.id();
        try {
            return restClient.get()
                    .uri("/api/orders/{orderId}", orderId)
                    .header(CurrentUser.HEADER, String.valueOf(customerId))
                    .retrieve()
                    .body(OrderDto.class);
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
