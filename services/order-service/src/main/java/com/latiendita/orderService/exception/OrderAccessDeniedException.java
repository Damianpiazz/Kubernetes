package com.latiendita.orderService.exception;

public class OrderAccessDeniedException extends RuntimeException {

    public OrderAccessDeniedException(Long id) {
        super("No tiene permiso sobre el pedido " + id);
    }
}
