package com.latiendita.orderService.order;

import com.latiendita.orderService.CurrentUser;
import com.latiendita.orderService.exception.InvalidOrderException;
import com.latiendita.orderService.exception.InvalidStateTransitionException;
import com.latiendita.orderService.exception.OrderAccessDeniedException;
import com.latiendita.orderService.exception.OrderNotFoundException;
import com.latiendita.orderService.exception.TotalMismatchException;
import com.latiendita.orderService.order.dto.CreateOrderRequest;
import com.latiendita.orderService.order.dto.ItemRequest;
import com.latiendita.orderService.order.dto.OrderMapper;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

    public static final String ORDER_EVENTS_TOPIC = "order-events";

    private final OrderRepository orderRepository;
    private final OutboxWriter outboxWriter;
    private final ProductClient productClient;

    public OrderResponse create(CreateOrderRequest request) {
        Long customerId = CurrentUser.id();
        validateItems(request);

        OrderEntity order = OrderEntity.builder()
                .orderNumber(UUID.randomUUID().toString())
                .customerId(customerId)
                .status(OrderStatus.PENDING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        BigDecimal total = BigDecimal.ZERO;
        for (ItemRequest item : request.items()) {
            ProductDto product = productClient.findById(item.productId());

            if (product.price() == null) {
                throw new InvalidOrderException(
                        "El producto " + product.productId() + " no tiene precio asignado");
            }

            BigDecimal unitPrice = product.price();
            BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(item.quantity()));

            order.addItem(OrderItemEntity.builder()
                    .order(order)
                    .productId(product.productId())
                    .productTitle(product.title())
                    .sku(product.sku())
                    .quantity(item.quantity())
                    .unitPrice(unitPrice)
                    .subtotal(subtotal)
                    .build());

            total = total.add(subtotal);
        }

        if (request.total() != null && request.total().compareTo(total) != 0) {
            throw new TotalMismatchException(request.total(), total);
        }
        order.setTotal(total);

        OrderEntity saved = orderRepository.save(order);

        outboxWriter.append(ORDER_EVENTS_TOPIC, saved.getId(), "ORDER_CREATED",
                new OrderCreatedEvent(saved.getId(), saved.getOrderNumber(),
                        saved.getCustomerId(), saved.getTotal(), itemsAsEvent(saved),
                        Instant.now()));

        return OrderMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(Long orderId) {
        return OrderMapper.toResponse(findOwnedEntity(orderId));
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> findByCustomer(Pageable pageable) {
        return orderRepository.findByCustomerId(CurrentUser.id(), pageable)
                .map(OrderMapper::toResponse);
    }

    public OrderResponse changeStatus(Long orderId, OrderStatus target) {
        if (target == OrderStatus.CANCELLED) {
            throw new InvalidOrderException(
                    "Para cancelar un pedido use POST /api/orders/{orderId}/cancel");
        }

        OrderEntity order = findOwnedEntity(orderId);
        OrderStatus from = order.getStatus();
        if (!from.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(from, target);
        }

        order.setStatus(target);
        order.setUpdatedAt(Instant.now());
        OrderEntity saved = orderRepository.save(order);

        outboxWriter.append(ORDER_EVENTS_TOPIC, saved.getId(), "ORDER_STATUS_CHANGED",
                new OrderStatusChangedEvent(saved.getId(), saved.getOrderNumber(),
                        saved.getCustomerId(), from, target, Instant.now()));

        return OrderMapper.toResponse(saved);
    }

    public OrderResponse cancel(Long orderId, String reason) {
        OrderEntity order = findOwnedEntity(orderId);
        if (!order.getStatus().cancellable()) {
            throw new InvalidStateTransitionException(order.getStatus(), OrderStatus.CANCELLED);
        }

        boolean refundRequired = order.getStatus() == OrderStatus.PAID;

        order.setStatus(OrderStatus.CANCELLED);
        order.setRefundRequired(refundRequired);
        order.setCancelReason(reason);
        order.setUpdatedAt(Instant.now());
        OrderEntity saved = orderRepository.save(order);

        Instant occurredAt = Instant.now();
        outboxWriter.append(ORDER_EVENTS_TOPIC, saved.getId(), "ORDER_CANCELLED",
                new OrderCancelledEvent(saved.getId(), saved.getOrderNumber(),
                        saved.getCustomerId(), reason, refundRequired, occurredAt));

        if (refundRequired) {
            outboxWriter.append(ORDER_EVENTS_TOPIC, saved.getId(), "REFUND_REQUESTED",
                    new RefundRequestedEvent(saved.getId(), saved.getOrderNumber(),
                            saved.getCustomerId(), saved.getTotal(), occurredAt));
        }

        return OrderMapper.toResponse(saved);
    }

    private void validateItems(CreateOrderRequest request) {
        if (request.items().isEmpty()) {
            throw new InvalidOrderException("El pedido debe tener al menos un ítem");
        }
        boolean duplicated = request.items().stream()
                .map(ItemRequest::productId)
                .distinct()
                .count() != request.items().size();
        if (duplicated) {
            throw new InvalidOrderException(
                    "No puede repetir el mismo productId en los ítems del pedido");
        }
    }

    private OrderEntity findOwnedEntity(Long orderId) {
        OrderEntity order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        if (!order.getCustomerId().equals(CurrentUser.id())) {
            throw new OrderAccessDeniedException(orderId);
        }
        return order;
    }

    private List<OrderCreatedEvent.Item> itemsAsEvent(OrderEntity order) {
        return order.getItems().stream()
                .map(item -> new OrderCreatedEvent.Item(item.getProductId(),
                        item.getQuantity(), item.getUnitPrice(), item.getSubtotal()))
                .toList();
    }
}
