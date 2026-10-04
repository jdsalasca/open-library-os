package com.openlibrary.isbn;

import java.util.Optional;

/**
 * A source of book data for an ISBN.
 *
 * <p>Exists only because there is now a second implementation. Both providers must
 * return an empty {@link Optional} rather than throw: the chain treats "I don't know
 * this book" and "I am broken" the same way, because from the catalogue's point of
 * view both mean "ask the next one, and if nobody knows, let the librarian type it".
 */
public interface IsbnProvider {

    /** Stored in the cache so the UI can say where each field came from. */
    String name();

    Optional<ExternalBook> lookup(Isbn isbn);
}
