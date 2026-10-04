package com.openlibrary.isbn;

import org.springframework.stereotype.Service;

/**
 * ISBN autofill: the cache in front of the provider chain.
 *
 * <p>ponytail: a thin bean whose only job is to be injectable. The logic lives in
 * {@link IsbnProviderChain}, which is a plain object and therefore testable without
 * Spring.
 */
@Service
public class IsbnLookupService {

    private final IsbnProviderChain chain;

    public IsbnLookupService(IsbnProviderChain chain) {
        this.chain = chain;
    }

    /** @throws LookupException 400 on an invalid ISBN, 404 when nobody knows it. */
    public ExternalBook lookup(String raw) {
        return chain.lookup(raw);
    }
}
