package com.latiendita.authService.exception;

public class DuplicateAccountException extends RuntimeException {

    public DuplicateAccountException(String field, String value) {
        super("El " + field + " '" + value + "' ya está en uso");
    }
}