package com.latiendita.paymentService.exception;

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

    @ExceptionHandler(PaymentNotFoundException.class)
    public ProblemDetail onPaymentNotFound(PaymentNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Pago no encontrado", ex.getMessage());
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail onOrderNotFound(OrderNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Pedido no encontrado", ex.getMessage());
    }

    @ExceptionHandler(PaymentAccessDeniedException.class)
    public ProblemDetail onAccessDenied(PaymentAccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Acceso denegado", ex.getMessage());
    }

    @ExceptionHandler(InvalidPaymentException.class)
    public ProblemDetail onInvalidPayment(InvalidPaymentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Pago inválido", ex.getMessage());
    }

    @ExceptionHandler(DuplicatePaymentException.class)
    public ProblemDetail onDuplicatePayment(DuplicatePaymentException ex) {
        return problem(HttpStatus.CONFLICT, "Pago duplicado", ex.getMessage());
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    public ProblemDetail onInvalidTransition(InvalidStateTransitionException ex) {
        return problem(HttpStatus.CONFLICT, "Transición de estado inválida", ex.getMessage());
    }

    @ExceptionHandler(MissingGatewayHeaderException.class)
    public ProblemDetail onMissingGatewayHeader(MissingGatewayHeaderException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "No autorizado", ex.getMessage());
    }

    @ExceptionHandler(PaymentGatewayUnavailableException.class)
    public ProblemDetail onGatewayUnavailable(PaymentGatewayUnavailableException ex) {
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
