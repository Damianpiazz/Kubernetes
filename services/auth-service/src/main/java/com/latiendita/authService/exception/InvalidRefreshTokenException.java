package com.latiendita.authService.exception;

public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Refresh token inválido o caducado");
    }
}