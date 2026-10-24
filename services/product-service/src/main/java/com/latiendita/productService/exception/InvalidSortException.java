package com.latiendita.productService.exception;

/**
 * `sort` con una propiedad que la entidad no expone o con un formato que Spring
 * Data no entiende (p. ej. el prefijo `-` o `campo:1`).
 *
 * Es un error del cliente, no del servidor: se lanza ANTES de llegar al
 * repositorio para responder 400 en lugar del 500 que devuelve JPA cuando
 * interpreta el sort recién al armar la query.
 */
public class InvalidSortException extends RuntimeException {

    public InvalidSortException(String message) {
        super(message);
    }
}
