package com.latiendita.paymentService.exception;

public class PaymentGatewayUnavailableException extends RuntimeException {

    public PaymentGatewayUnavailableException(String message) {
        super(message);
    }

    public PaymentGatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public PaymentGatewayUnavailableException(Throwable cause) {
        this("El proveedor de pagos no está disponible", cause);
    }
}
