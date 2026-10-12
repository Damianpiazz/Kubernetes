package com.latiendita.gateway.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GatewayErrorController implements ErrorController {

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ProblemDetail onError(HttpServletRequest request) {
        HttpStatus status = statusOf(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
        return problem(status);
    }

    private static HttpStatus statusOf(Object statusCode) {
        if (statusCode instanceof Integer code) {
            HttpStatus resolved = HttpStatus.resolve(code);
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static ProblemDetail problem(HttpStatus status) {
        ProblemDetail pd = ProblemDetail.forStatus(status);
        pd.setTitle(switch (status) {
            case NOT_FOUND -> "Ruta no encontrada";
            case BAD_GATEWAY, SERVICE_UNAVAILABLE -> "Servicio no disponible";
            case GATEWAY_TIMEOUT -> "Tiempo de espera agotado";
            default -> "Error en el gateway";
        });
        pd.setDetail(switch (status) {
            case NOT_FOUND -> "No existe ninguna ruta registrada para este recurso";
            case BAD_GATEWAY, SERVICE_UNAVAILABLE -> "El microservicio de destino no está disponible";
            case GATEWAY_TIMEOUT -> "El microservicio de destino no respondió a tiempo";
            default -> "Unexpected error";
        });
        return pd;
    }
}
