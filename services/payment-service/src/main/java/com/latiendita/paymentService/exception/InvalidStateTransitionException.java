package com.latiendita.paymentService.exception;

import com.latiendita.paymentService.payment.PaymentStatus;

public class InvalidStateTransitionException extends RuntimeException {

    public InvalidStateTransitionException(PaymentStatus from, PaymentStatus to) {
        super("No se puede pasar de " + from + " a " + to);
    }
}
