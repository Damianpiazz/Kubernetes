package com.latiendita.userService.exception;

public class DuplicateUserException extends RuntimeException {

    public DuplicateUserException(String field, String value) {
        super("El " + field + " '" + value + "' ya está en uso");
    }
}
