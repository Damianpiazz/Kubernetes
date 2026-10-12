package com.latiendita.paymentService.exception;

public class MissingGatewayHeaderException extends RuntimeException {

    public MissingGatewayHeaderException() {
        super("Falta o es inválido el header 'X-User-Id'; la petición debe pasar por el Gateway");
    }
}
