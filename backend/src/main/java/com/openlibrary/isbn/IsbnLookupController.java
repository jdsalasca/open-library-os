package com.openlibrary.isbn;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/isbn/{isbn} — fill a book from a public API.
 *
 * <p>Answers 400 when the ISBN is invalid and 404 when nobody knows it, so the UI can
 * offer the manual form in both cases instead of showing a stack trace.
 */
@RestController
@RequestMapping("/isbn")
public class IsbnLookupController {

    private final IsbnLookupService lookup;

    public IsbnLookupController(IsbnLookupService lookup) {
        this.lookup = lookup;
    }

    @GetMapping("/{isbn}")
    public ExternalBook lookup(@PathVariable String isbn) {
        return lookup.lookup(isbn);
    }
}
