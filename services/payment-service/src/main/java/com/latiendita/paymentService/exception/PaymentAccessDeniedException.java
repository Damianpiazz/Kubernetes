package com.latiendita.paymentService.exception;

public class PaymentAccessDeniedException extends RuntimeException {

    public PaymentAccessDeniedException(Long paymentId) {
        super("No tiene permiso sobre el pago " + paymentId);
    }
}
