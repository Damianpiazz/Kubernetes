package com.latiendita.orderService.exception;

public class ProductServiceUnavailableException extends RuntimeException {

    public ProductServiceUnavailableException(Throwable cause) {
        super("El servicio de productos no está disponible", cause);
    }
}
