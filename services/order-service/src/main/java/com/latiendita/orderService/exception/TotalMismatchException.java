package com.latiendita.orderService.exception;

import java.math.BigDecimal;

public class TotalMismatchException extends RuntimeException {

    public TotalMismatchException(BigDecimal sent, BigDecimal calculated) {
        super("El total enviado (" + sent + ") no coincide con el total calculado en el servidor ("
                + calculated + ")");
    }
}
