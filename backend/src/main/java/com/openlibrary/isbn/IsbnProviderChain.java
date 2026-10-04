package com.openlibrary.isbn;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Cache first, then each provider in order, remembering the answer either way.
 *
 * <p>An empty answer from every provider is cached too: without that, somebody typing
 * an unknown ISBN would hit the outside APIs on every single attempt.
 */
public class IsbnProviderChain {

    private final IsbnCache cache;
    private final List<IsbnProvider> providers;

    public IsbnProviderChain(IsbnCache cache, List<IsbnProvider> providers) {
        this.cache = cache;
        this.providers = providers;
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

        // isCached() separates "never asked" from "asked and nobody knew": both return
        // empty from get(), but only the second must skip the network.
        if (cache.isCached(key)) {
            return cache.get(key).orElseThrow(() -> notFound(key));
        }

        for (IsbnProvider provider : providers) {
            Optional<ExternalBook> found = provider.lookup(isbn);
            if (found.isPresent()) {
                cache.put(key, provider.name(), found.get());
                return found.get();
            }
        }

        cache.put(key, "none", null);
        throw notFound(key);
    }

    private static LookupException notFound(String key) {
        return new LookupException(HttpStatus.NOT_FOUND, "not_found",
                "Ningun proveedor conoce el ISBN " + key + ".");
    }
}
