package com.openlibrary.isbn;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Cache first, then each provider in order, remembering the answer either way.
 *
 * <p>Every provider call goes through a {@link CircuitBreaker}, so an outside API that
 * is down costs one timeout instead of one per ISBN typed.
 *
 * <p>An empty answer from every provider is cached too: without that, somebody typing
 * an unknown ISBN would hit the outside APIs on every single attempt.
 */
public class IsbnProviderChain {

    private final IsbnCache cache;
    private final List<IsbnProvider> providers;
    private final CircuitBreaker breaker;

    public IsbnProviderChain(IsbnCache cache, List<IsbnProvider> providers) {
        this(cache, providers, null);
    }

    /** @param breaker may be null, in which case every call goes straight through. */
    public IsbnProviderChain(IsbnCache cache, List<IsbnProvider> providers, CircuitBreaker breaker) {
        this.cache = cache;
        this.providers = providers;
        this.breaker = breaker;
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
            Optional<ExternalBook> found = callProvider(provider, isbn);
            if (found.isPresent()) {
                cache.put(key, provider.name(), found.get());
                return found.get();
            }
        }

        cache.put(key, "none", null);
        throw notFound(key);
    }

    /**
     * A provider that throws, or whose circuit is open, counts as "no data" so one dead
     * API cannot break the autofill while another one still works.
     *
     * <p>The call goes through the breaker <em>before</em> the failure is swallowed:
     * catching inside the supplier would hide it from the breaker, which would then
     * never see a failure and never trip.
     */
    private Optional<ExternalBook> callProvider(IsbnProvider provider, Isbn isbn) {
        java.util.function.Supplier<Optional<ExternalBook>> call = () -> {
            try {
                return provider.lookup(isbn);
            } catch (RuntimeException e) {
                // The provider could not answer; rethrow wrapped so the breaker can
                // classify it as a provider fault instead of a bug on our side.
                throw new ProviderUnavailable(provider.name(), e);
            }
        };
        try {
            Optional<ExternalBook> answer =
                    breaker == null ? call.get() : breaker.call(provider.name(), call);
            // A breaker that skipped the call returns null, not an empty Optional.
            return answer == null ? Optional.empty() : answer;
        } catch (RuntimeException e) {
            // ProviderUnavailable, an open circuit, or any other failure: from the
            // catalogue's point of view this provider simply has no answer.
            return Optional.empty();
        }
    }

    /** Marks a provider that failed for reasons outside our code, so it counts as a fault. */
    static final class ProviderUnavailable extends RuntimeException {
        ProviderUnavailable(String provider, Throwable cause) {
            super("provider " + provider + " unavailable", cause);
        }
    }

    private static LookupException notFound(String key) {
        return new LookupException(HttpStatus.NOT_FOUND, "not_found",
                "Ningun proveedor conoce el ISBN " + key + ".");
    }
}
