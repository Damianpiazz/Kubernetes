package com.latiendita.userService;

import com.latiendita.userService.exception.MissingGatewayHeaderException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Identidad que inyecta el gateway sobre la request.
 *
 * `X-User-Id` es el `sub` del JWT (id de la cuenta en auth-service), y
 * `X-User-Email` es el claim `email`. El perfil se busca por email porque el
 * id de esta base de datos es el de la entidad `users`, que se crea aparte (por
 * el evento `account-events`) y no coincide con el id de la cuenta.
 */
public final class CurrentUser {

    public static final String USER_HEADER = "X-User-Id";
    public static final String EMAIL_HEADER = "X-User-Email";

    private CurrentUser() {}

    public static Long id() {
        String value = header(USER_HEADER);
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new MissingGatewayHeaderException();
        }
    }

    public static String email() {
        return header(EMAIL_HEADER);
    }

    private static String header(String name) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            throw new MissingGatewayHeaderException();
        }
        String value = servletAttributes.getRequest().getHeader(name);
        if (value == null || value.isBlank()) {
            throw new MissingGatewayHeaderException();
        }
        return value.trim();
    }
}
