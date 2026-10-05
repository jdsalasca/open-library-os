package com.openlibrary.shared;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.List;

/** Every error leaves the API as an RFC 9457 ProblemDetail. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApi(ApiException e, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setType(URI.create("https://openlibrary.os/problems/" + e.code()));
        problem.setTitle(e.status().getReasonPhrase());
        problem.setProperty("code", e.code());
        if (request != null) {
            problem.setProperty("path", request.getRequestURI());
        }
        if (!e.fieldErrors().isEmpty()) {
            problem.setProperty("fieldErrors", e.fieldErrors());
        }
        return problem;
    }

    /** Bean Validation failures become per-field messages the forms can render. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        List<ApiException.FieldError> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiException.FieldError(f.getField(), f.getDefaultMessage()))
                .toList();

        return handleApi(new ApiException(HttpStatus.BAD_REQUEST, "validation",
                "Revisa los datos introducidos.", fields), null);
    }

    /**
 * A body that does not match the record (missing field, wrong type, empty payload)
 * is the caller's mistake, not a server fault.
 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadable(HttpMessageNotReadableException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "El cuerpo de la peticion no es valido.");
        problem.setType(URI.create("https://openlibrary.os/problems/malformed_body"));
        problem.setTitle("Bad Request");
        problem.setProperty("code", "malformed_body");
        return problem;
    }

    /** The path exists but not for this verb: 405, never a 500. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED,
                "Ese recurso no admite el metodo " + e.getMethod() + ".");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleDenied(AccessDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
                "No tienes permiso para esta accion.");
    }

    @ExceptionHandler(AuthenticationException.class)
    ProblemDetail handleUnauthenticated(AuthenticationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED,
                "Sesion no iniciada o caducada.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleMissing(NoResourceFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Recurso no encontrado.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("unhandled error on {}", request.getRequestURI(), e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Error interno. Revisa los registros del servidor.");
    }
}