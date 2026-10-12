package com.latiendita.paymentService.exception;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(Long id) {
        super("Pago no encontrado con id " + id);
    }

    public PaymentNotFoundException(String message) {
        super(message);
    }
}
