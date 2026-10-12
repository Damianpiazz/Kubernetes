package com.latiendita.orderService.order;

public enum OrderStatus {
    PENDING, PAID, SHIPPED, DELIVERED, CANCELLED;

    public boolean canTransitionTo(OrderStatus target) {
        return switch (this) {
            case PENDING -> target == PAID || target == CANCELLED;
            case PAID -> target == SHIPPED || target == CANCELLED;
            case SHIPPED -> target == DELIVERED;
            case DELIVERED, CANCELLED -> false;
        };
    }

    public boolean cancellable() {
        return this == PENDING || this == PAID;
    }
}
