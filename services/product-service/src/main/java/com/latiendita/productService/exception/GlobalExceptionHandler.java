package com.latiendita.productService.exception;

import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ProblemDetail onProductNotFound(ProductNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Producto no encontrado", ex.getMessage());
    }

    @ExceptionHandler(CategoryNotFoundException.class)
    public ProblemDetail onCategoryNotFound(CategoryNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Categoría no encontrada", ex.getMessage());
    }

    @ExceptionHandler(DuplicateSkuException.class)
    public ProblemDetail onDuplicateSku(DuplicateSkuException ex) {
        return problem(HttpStatus.CONFLICT, "Recurso duplicado", ex.getMessage());
    }

    @ExceptionHandler(DuplicateCategoryException.class)
    public ProblemDetail onDuplicateCategory(DuplicateCategoryException ex) {
        return problem(HttpStatus.CONFLICT, "Recurso duplicado", ex.getMessage());
    }

    @ExceptionHandler(InvalidSortException.class)
    public ProblemDetail onInvalidSort(InvalidSortException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Sort inválido", ex.getMessage());
    }

    /**
     * Red de seguridad: todo lo que Spring Data considera "uso inválido de la
     * API" es un problema del request, no del servidor, así que responde 400 y
     * nunca 500.
     */
    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ProblemDetail onInvalidApiUsage(InvalidDataAccessApiUsageException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Solicitud inválida", ex.getMessage());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail onOptimisticLock(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "Conflicto de concurrencia",
                "El recurso fue modificado por otra operación; reintente la petición");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail onDataIntegrity(DataIntegrityViolationException ex) {
        return problem(HttpStatus.CONFLICT, "Conflicto de datos",
                "Violación de restricción única (posible carrera entre peticiones simultáneas)");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidation(MethodArgumentNotValidException ex) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Datos inválidos",
                "Falló la validación del request");
        pd.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.toList()));
        return pd;
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setTitle(title);
        pd.setDetail(detail);
        return pd;
    }
}
