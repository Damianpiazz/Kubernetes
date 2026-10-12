package com.latiendita.orderService.exception;

import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail onOrderNotFound(OrderNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Pedido no encontrado", ex.getMessage());
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ProblemDetail onProductNotFound(ProductNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Producto no encontrado", ex.getMessage());
    }

    @ExceptionHandler(OrderAccessDeniedException.class)
    public ProblemDetail onAccessDenied(OrderAccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Acceso denegado", ex.getMessage());
    }

    @ExceptionHandler({InvalidOrderException.class, TotalMismatchException.class})
    public ProblemDetail onInvalidOrder(RuntimeException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Pedido inválido", ex.getMessage());
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ProblemDetail onInvalidTransition(InvalidStateTransitionException ex) {
        return problem(HttpStatus.CONFLICT, "Transición de estado inválida", ex.getMessage());
    }

    @ExceptionHandler(MissingGatewayHeaderException.class)
    public ProblemDetail onMissingGatewayHeader(MissingGatewayHeaderException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "No autorizado", ex.getMessage());
    }

    @ExceptionHandler(ProductServiceUnavailableException.class)
    public ProblemDetail onProductServiceDown(ProductServiceUnavailableException ex) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Servicio no disponible", ex.getMessage());
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
