package com.latiendita.userService.exception;

/**
 * El request llegó sin los headers de identidad que inyecta el gateway
 * (`X-User-Id` / `X-User-Email`): se llamó al servicio sin pasar por él.
 */
public class MissingGatewayHeaderException extends RuntimeException {

    public MissingGatewayHeaderException() {
        super("Falta la identidad del usuario propagada por el gateway");
    }
}
