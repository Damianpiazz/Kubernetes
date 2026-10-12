package com.latiendita.orderService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.latiendita.orderService.exception.InvalidOrderException;
import com.latiendita.orderService.exception.InvalidStateTransitionException;
import com.latiendita.orderService.exception.OrderAccessDeniedException;
import com.latiendita.orderService.exception.OrderNotFoundException;
import com.latiendita.orderService.exception.TotalMismatchException;
import com.latiendita.orderService.order.OrderEntity;
import com.latiendita.orderService.order.OrderItemEntity;
import com.latiendita.orderService.order.OrderRepository;
import com.latiendita.orderService.order.OrderService;
import com.latiendita.orderService.order.OrderStatus;
import com.latiendita.orderService.order.dto.CreateOrderRequest;
import com.latiendita.orderService.order.dto.ItemRequest;
import com.latiendita.orderService.order.dto.OrderResponse;
import com.latiendita.orderService.order.dto.event.OrderCancelledEvent;
import com.latiendita.orderService.order.dto.event.OrderCreatedEvent;
import com.latiendita.orderService.order.dto.event.OrderStatusChangedEvent;
import com.latiendita.orderService.order.dto.event.RefundRequestedEvent;
import com.latiendita.orderService.outbox.OutboxWriter;
import com.latiendita.orderService.product.ProductClient;
import com.latiendita.orderService.product.ProductDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final Long CUSTOMER = 1L;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OutboxWriter outboxWriter;

    @Mock
    private ProductClient productClient;

    @InjectMocks
    private OrderService orderService;

    @BeforeEach
    void setCurrentUser() {
        setCurrentUserHeader(String.valueOf(CUSTOMER));
    }

    @AfterEach
    void resetCurrentUser() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldCreateOrderCalculatingTotalFromServerPrices() {
        when(productClient.findById(42L)).thenReturn(product(42L, "NB-LEN-001", "999.99"));
        when(productClient.findById(7L)).thenReturn(product(7L, "MS-001", "49.50"));
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
            OrderEntity order = invocation.getArgument(0);
            order.setId(1L);
            return order;
        });

        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new ItemRequest(42L, 2), new ItemRequest(7L, 1)), null);

        OrderResponse response = orderService.create(request);

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.customerId()).isEqualTo(CUSTOMER);
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.total()).isEqualByComparingTo("2049.48");
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).unitPrice()).isEqualByComparingTo("999.99");
        assertThat(response.items().get(0).subtotal()).isEqualByComparingTo("1999.98");
        assertThat(response.items().get(0).sku()).isEqualTo("NB-LEN-001");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).append(eq(OrderService.ORDER_EVENTS_TOPIC), eq(1L),
                eq("ORDER_CREATED"), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(OrderCreatedEvent.class);
    }

    @Test
    void shouldRejectClientTotalThatDoesNotMatchCalculated() {
        when(productClient.findById(42L)).thenReturn(product(42L, "NB-LEN-001", "999.99"));

        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new ItemRequest(42L, 1)), new BigDecimal("1.00"));

        assertThatThrownBy(() -> orderService.create(request))
                .isInstanceOf(TotalMismatchException.class);

        verify(outboxWriter, never()).append(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void shouldAcceptClientTotalWhenItMatchesCalculated() {
        when(productClient.findById(42L)).thenReturn(product(42L, "NB-LEN-001", "999.99"));
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation -> {
            OrderEntity order = invocation.getArgument(0);
            order.setId(1L);
            return order;
        });

        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new ItemRequest(42L, 2)), new BigDecimal("1999.98"));

        OrderResponse response = orderService.create(request);

        assertThat(response.total()).isEqualByComparingTo("1999.98");
    }

    @Test
    void shouldRejectDuplicatedProductInItems() {
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new ItemRequest(42L, 1), new ItemRequest(42L, 2)), null);

        assertThatThrownBy(() -> orderService.create(request))
                .isInstanceOf(InvalidOrderException.class);

        verify(outboxWriter, never()).append(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void shouldRejectEmptyItems() {
        CreateOrderRequest request = new CreateOrderRequest(List.of(), null);

        assertThatThrownBy(() -> orderService.create(request))
                .isInstanceOf(InvalidOrderException.class);
    }

    @Test
    void shouldThrowOrderNotFoundOnFindById() {
        when(orderRepository.findWithItemsById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderService.findById(99L))
                .isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void shouldDenyAccessToOrderOfAnotherCustomer() {
        when(orderRepository.findWithItemsById(5L))
                .thenReturn(Optional.of(order(999L, OrderStatus.PENDING, "10.00")));

        assertThatThrownBy(() -> orderService.findById(5L))
                .isInstanceOf(OrderAccessDeniedException.class);
    }

    @Test
    void shouldChangeStatusPENDINGtoPAID() {
        OrderEntity pending = order(CUSTOMER, OrderStatus.PENDING, "10.00");
        pending.setId(5L);
        when(orderRepository.findWithItemsById(5L)).thenReturn(Optional.of(pending));
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation ->
                invocation.getArgument(0));

        OrderResponse response = orderService.changeStatus(5L, OrderStatus.PAID);

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).append(eq(OrderService.ORDER_EVENTS_TOPIC), eq(5L),
                eq("ORDER_STATUS_CHANGED"), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(OrderStatusChangedEvent.class);
    }

    @Test
    void shouldRejectInvalidTransition() {
        OrderEntity pending = order(CUSTOMER, OrderStatus.PENDING, "10.00");
        when(orderRepository.findWithItemsById(5L)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> orderService.changeStatus(5L, OrderStatus.DELIVERED))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void shouldRejectCancelledViaStatusEndpoint() {
        assertThatThrownBy(() -> orderService.changeStatus(5L, OrderStatus.CANCELLED))
                .isInstanceOf(InvalidOrderException.class);
    }

    @Test
    void shouldCancelPendingOrderWithoutRefund() {
        OrderEntity pending = order(CUSTOMER, OrderStatus.PENDING, "10.00");
        pending.setId(5L);
        when(orderRepository.findWithItemsById(5L)).thenReturn(Optional.of(pending));
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation ->
                invocation.getArgument(0));

        OrderResponse response = orderService.cancel(5L, "Me arrepentí");

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(response.refundRequired()).isFalse();
        assertThat(response.cancelReason()).isEqualTo("Me arrepentí");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).append(eq(OrderService.ORDER_EVENTS_TOPIC), eq(5L),
                eq("ORDER_CANCELLED"), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(OrderCancelledEvent.class);

        verify(outboxWriter, never()).append(eq(OrderService.ORDER_EVENTS_TOPIC), eq(5L),
                eq("REFUND_REQUESTED"), any());
    }

    @Test
    void shouldCancelPaidOrderRequestingRefund() {
        OrderEntity paid = order(CUSTOMER, OrderStatus.PAID, "100.00");
        paid.setId(5L);
        when(orderRepository.findWithItemsById(5L)).thenReturn(Optional.of(paid));
        when(orderRepository.save(any(OrderEntity.class))).thenAnswer(invocation ->
                invocation.getArgument(0));

        OrderResponse response = orderService.cancel(5L, "Pago duplicado");

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(response.refundRequired()).isTrue();

        ArgumentCaptor<Object> cancelled = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).append(eq(OrderService.ORDER_EVENTS_TOPIC), eq(5L),
                eq("ORDER_CANCELLED"), cancelled.capture());
        assertThat(cancelled.getValue()).isInstanceOf(OrderCancelledEvent.class);

        ArgumentCaptor<Object> refund = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).append(eq(OrderService.ORDER_EVENTS_TOPIC), eq(5L),
                eq("REFUND_REQUESTED"), refund.capture());
        assertThat(refund.getValue()).isInstanceOf(RefundRequestedEvent.class);
        assertThat(((RefundRequestedEvent) refund.getValue()).amount())
                .isEqualByComparingTo("100.00");
    }

    @Test
    void shouldRejectCancelWhenShipped() {
        OrderEntity shipped = order(CUSTOMER, OrderStatus.SHIPPED, "10.00");
        when(orderRepository.findWithItemsById(5L)).thenReturn(Optional.of(shipped));

        assertThatThrownBy(() -> orderService.cancel(5L, "ya salió"))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    private void setCurrentUserHeader(String customerId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", customerId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private ProductDto product(Long id, String sku, String price) {
        return new ProductDto(id, "Producto " + sku, "https://cdn/" + sku + ".png",
                sku, new BigDecimal(price), 10, 7L);
    }

    private OrderEntity order(Long customerId, OrderStatus status, String total) {
        OrderEntity order = OrderEntity.builder()
                .orderNumber(UUID.randomUUID().toString())
                .customerId(customerId)
                .status(status)
                .total(new BigDecimal(total))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        order.addItem(OrderItemEntity.builder()
                .order(order)
                .productId(42L)
                .productTitle("Producto NB-LEN-001")
                .sku("NB-LEN-001")
                .quantity(1)
                .unitPrice(new BigDecimal(total))
                .subtotal(new BigDecimal(total))
                .build());
        return order;
    }
}
