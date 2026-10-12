package com.latiendita.orderService.order.dto;

import com.latiendita.orderService.order.OrderEntity;
import com.latiendita.orderService.order.OrderItemEntity;

public final class OrderMapper {

    private OrderMapper() {}

    public static OrderResponse toResponse(OrderEntity entity) {
        return new OrderResponse(
                entity.getId(),
                entity.getOrderNumber(),
                entity.getCustomerId(),
                entity.getStatus(),
                entity.getTotal(),
                entity.isRefundRequired(),
                entity.getCancelReason(),
                entity.getItems().stream().map(OrderMapper::toItemResponse).toList(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static OrderItemResponse toItemResponse(OrderItemEntity item) {
        return new OrderItemResponse(
                item.getProductId(),
                item.getProductTitle(),
                item.getSku(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getSubtotal());
    }
}
