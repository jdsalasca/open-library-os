package com.openlibrary.isbn;

import org.springframework.http.HttpStatus;

/**
 * A lookup the caller can fix, carrying a stable code for the UI.
 *
 * <p>ponytail: lives here rather than in a shared package because {@code shared/} is
 * still being written on another branch. Once its {@code ApiException} lands in
 * develop, this should be deleted in favour of it — the HTTP contract stays the same.
 */
class LookupException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    LookupException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
