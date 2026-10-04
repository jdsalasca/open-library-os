package com.openlibrary.isbn;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * ISBN autofill: cache first, then the provider, and remember the answer either way.
 *
 * <p>ponytail: one provider wired directly. A provider chain or a strategy interface
 * arrives with Google Books, not before.
 */
@Service
public class IsbnLookupService {

    private final IsbnCache cache;
    private final OpenLibraryProvider openLibrary;

    public IsbnLookupService(IsbnCache cache, OpenLibraryProvider openLibrary) {
        this.cache = cache;
        this.openLibrary = openLibrary;
    }

    /**
     * @throws LookupException 400 when the ISBN is invalid, 404 when nobody knows it;
     *         the UI then offers the manual form instead of showing a provider error.
     */
    @Transactional
    public ExternalBook lookup(String raw) {
        Isbn isbn = Isbn.parse(raw);
        if (isbn == null) {
            throw new LookupException(HttpStatus.BAD_REQUEST, "invalid_isbn",
                    "El ISBN no es valido: " + raw);
        }

        String key = isbn.normalised();

        // A cached miss must not be retried, hence the separate isCached() check.
        if (cache.isCached(key)) {
            return cache.get(key).orElseThrow(() -> notFound(key));
        }

        Optional<ExternalBook> found = openLibrary.lookup(isbn);
        // A miss is cached too: otherwise a librarian typing an unknown ISBN would
        // hit the outside API on every single attempt.
        cache.put(key, "openlibrary", found.orElse(null));

        return found.orElseThrow(() -> notFound(key));
    }

    private static LookupException notFound(String key) {
        return new LookupException(HttpStatus.NOT_FOUND, "not_found",
                "Ningun proveedor conoce el ISBN " + key + ".");
    }
}
