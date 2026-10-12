package com.latiendita.orderService.exception;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(Long id) {
        super("Pedido no encontrado con id " + id);
    }
}
