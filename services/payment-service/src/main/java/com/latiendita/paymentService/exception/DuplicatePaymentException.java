package com.latiendita.paymentService.exception;

public class DuplicatePaymentException extends RuntimeException {

    public DuplicatePaymentException(Long orderId) {
        super("El pedido " + orderId + " ya tiene un pago aprobado");
    }
}
