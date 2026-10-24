package com.latiendita.productService.sort;

import com.latiendita.productService.exception.InvalidSortException;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.data.domain.Sort;

/**
 * Valida el `sort` de un {@link org.springframework.data.domain.Pageable} contra
 * las propiedades que la entidad expone.
 *
 * Spring Data resuelve el sort por nombre de propiedad de la entidad y sólo lo
 * valida al armar la query en JPA: con una propiedad desconocida responde 500
 * ({@code InvalidDataAccessApiUsageException}) en vez de 400. Se valida antes
 * de tocar el repositorio para devolver un error de formato claro.
 *
 * Formato aceptado: `propiedad` (ascendente por default) o
 * `propiedad,asc|desc`, con propiedades de la allowlist.
 */
public final class SortValidator {

    private SortValidator() {
    }

    public static void validate(Sort sort, Set<String> allowedProperties) {
        for (Sort.Order order : sort) {
            String property = order.getProperty();
            if (!allowedProperties.contains(property)) {
                throw new InvalidSortException(message(property, allowedProperties));
            }
        }
    }

    private static String message(String property, Set<String> allowedProperties) {
        String valid = new TreeSet<>(allowedProperties).stream().collect(Collectors.joining(", "));
        String format = "Use `propiedad` o `propiedad,asc|desc` (ej.: id,desc). "
                + "Propiedades válidas: " + valid;

        if (property.startsWith("-") || property.contains(":")) {
            return "Formato de sort inválido: '" + property + "'. "
                    + "No se acepta el prefijo `-` ni el estilo `campo:1`. " + format;
        }
        return "Propiedad de sort no soportada: '" + property + "'. " + format;
    }
}
