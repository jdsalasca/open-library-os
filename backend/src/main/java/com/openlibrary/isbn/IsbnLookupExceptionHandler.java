package com.openlibrary.isbn;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns lookup failures into RFC 9457 responses the UI can branch on. */
@RestControllerAdvice(assignableTypes = IsbnLookupController.class)
class IsbnLookupExceptionHandler {

    @ExceptionHandler(LookupException.class)
    ProblemDetail handle(LookupException e) {
        var problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setTitle(e.status().getReasonPhrase());
        problem.setProperty("code", e.code());
        return problem;
    }
}
