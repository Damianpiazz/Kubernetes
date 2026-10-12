package com.latiendita.orderService.order.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.latiendita.orderService.order.OrderEntity;
import com.latiendita.orderService.order.OrderRepository;
import com.latiendita.orderService.order.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PaymentApprovedConsumerTest {

    private static final String APPROVED = """
            {"type":"PAYMENT_APPROVED","paymentId":1,"orderId":7,"orderNumber":"ORD-7",\
            "customerId":1,"amount":2049.48,"currency":"ARS","provider":"MERCADO_PAGO",\
            "providerPaymentId":"12345","occurredAt":"2026-10-12T12:00:00Z"}
            """;

    @Mock
    private OrderRepository orderRepository;

    private PaymentApprovedConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentApprovedConsumer(orderRepository, new ObjectMapper());
    }

    @Test
    void shouldMovePendingOrderToPaid() {
        OrderEntity order = order(OrderStatus.PENDING);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);

        consumer.onPaymentEvent(APPROVED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        verify(orderRepository).save(order);
    }

    @Test
    void shouldIgnoreEventWhenOrderIsNotPending() {
        OrderEntity order = order(OrderStatus.CANCELLED);
        when(orderRepository.findById(7L)).thenReturn(Optional.of(order));

        consumer.onPaymentEvent(APPROVED);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderRepository, never()).save(order);
    }

    @Test
    void shouldIgnoreNonApprovedEventTypes() {
        consumer.onPaymentEvent("""
                {"type":"PAYMENT_CREATED","paymentId":1,"orderId":7}
                """);
        consumer.onPaymentEvent("""
                {"type":"PAYMENT_REJECTED","paymentId":1,"orderId":7}
                """);
        consumer.onPaymentEvent("""
                {"type":"PAYMENT_CANCELLED","paymentId":1,"orderId":7}
                """);

        verify(orderRepository, never()).findById(org.mockito.ArgumentMatchers.anyLong());
        verify(orderRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldIgnoreMalformedJsonWithoutFailing() {
        consumer.onPaymentEvent("esto no es json {{{");
        consumer.onPaymentEvent("");

        verify(orderRepository, never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void shouldFailWhenOrderDoesNotExistSoKafkaRetries() {
        when(orderRepository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> consumer.onPaymentEvent(APPROVED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pedido inexistente");
    }

    private OrderEntity order(OrderStatus status) {
        OrderEntity order = OrderEntity.builder()
                .orderNumber(UUID.randomUUID().toString())
                .customerId(1L)
                .status(status)
                .total(new BigDecimal("2049.48"))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        order.setId(7L);
        return order;
    }
}
