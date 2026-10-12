package com.latiendita.orderService.exception;

import com.latiendita.orderService.order.OrderStatus;

public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(OrderStatus from, OrderStatus to) {
        super("No se puede pasar de " + from + " a " + to);
    }
}
