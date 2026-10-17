package com.latiendita.productService.exception;

public class DuplicateCategoryException extends RuntimeException {

    public DuplicateCategoryException(String name) {
        super("La categoría '" + name + "' ya existe");
    }
}
