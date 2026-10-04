package com.openlibrary.isbn;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The chain under a failing provider: the breaker must stop hammering a dead API and
 * let the next one answer.
 *
 * <p>This is the behaviour a librarian actually feels: Open Library unreachable must
 * not add its timeout to every ISBN they type.
 */
class IsbnProviderChainBreakerTest {

    private static final class FakeCache extends IsbnCache {
        private final java.util.Map<String, ExternalBook> stored = new java.util.HashMap<>();
        private final java.util.Map<String, String> sources = new java.util.HashMap<>();

        FakeCache() {
            super(null, null);
        }

        @Override
        public Optional<ExternalBook> get(String isbn) {
            return Optional.ofNullable(stored.get(isbn));
        }

        @Override
        public boolean isCached(String isbn) {
            return sources.containsKey(isbn);
        }

        @Override
        public void put(String isbn, String source, ExternalBook book) {
            sources.put(isbn, source);
            if (book == null) {
                stored.remove(isbn);
            } else {
                stored.put(isbn, book);
            }
        }
    }

    /** Counts how many times the chain actually reached it. */
    private static final class Counting implements IsbnProvider {
        private final String name;
        private final int[] calls;
        private final boolean broken;

        Counting(String name, int[] calls, boolean broken) {
            this.name = name;
            this.calls = calls;
            this.broken = broken;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Optional<ExternalBook> lookup(Isbn isbn) {
            calls[0]++;
            if (broken) {
                // What a real provider raises when the network is gone.
                throw new UncheckedIOException(new java.io.IOException("unreachable"));
            }
            // The source must be this provider's own, like the real implementations do.
            return Optional.of(new ExternalBook.Mutable(name,
                    isbn.normalised(), "T", null, List.of(), "Ace", 1984,
                    List.of(), "en", 100, null, null).toRecord());
        }
    }

    private static ExternalBook from(String provider, String isbn) {
        return new ExternalBook.Mutable(provider, isbn, "T", null, List.of(), "Ace", 1984,
                List.of(), "en", 100, null, null).toRecord();
    }

    @Test
    void keepsAskingTheNextProviderWhileTheFirstOneIsDown() {
        FakeCache cache = new FakeCache();
        int[] openLibrary = {0};
        int[] google = {0};
        var breaker = new CircuitBreaker(2, Duration.ofMinutes(5), () -> 1_700_000_000_000L);

        // Both providers throw: the chain must surface not_found, not an error.
        var chain = new IsbnProviderChain(cache, List.of(
                new Counting("openlibrary", openLibrary, true),
                new Counting("googlebooks", google, true)), breaker);

        assertThatThrownBy(() -> chain.lookup("9780306406157"))
                .isInstanceOf(LookupException.class);
        assertThat(openLibrary[0]).isPositive();
        assertThat(google[0]).as("the chain must try the second provider").isPositive();
    }

    @Test
    void stopsCallingADeadProviderOnceItsCircuitIsOpen() {
        FakeCache cache = new FakeCache();
        int[] openLibrary = {0};
        int[] google = {0};
        long[] now = {1_700_000_000_000L};
        var breaker = new CircuitBreaker(2, Duration.ofMinutes(5), () -> now[0]);

        var chain = new IsbnProviderChain(cache, List.of(
                new Counting("openlibrary", openLibrary, true),
                new Counting("googlebooks", google, false)), breaker);

        // Every lookup fails on Open Library; Google is healthy so the chain still
        // answers, which is exactly the situation we want to survive.
        assertThat(chain.lookup("9788437600000").source()).isEqualTo("googlebooks");
        assertThat(breaker.isOpen("openlibrary")).as("one failure is not enough").isFalse();

        assertThat(chain.lookup("9788437600017").source()).isEqualTo("googlebooks");
        assertThat(breaker.isOpen("openlibrary")).as("two failures open the circuit").isTrue();

        int callsAfterOpening = openLibrary[0];

        // Third ISBN: the dead provider is skipped entirely.
        ExternalBook third = chain.lookup("9788437600024");

        assertThat(third.source()).isEqualTo("googlebooks");
        assertThat(openLibrary[0])
                .as("an open circuit must not be retried").isEqualTo(callsAfterOpening);
    }

    @Test
    void reportsNotFoundWhenEveryProviderIsDown() {
        FakeCache cache = new FakeCache();
        var breaker = new CircuitBreaker(2, Duration.ofMinutes(5), () -> 1_700_000_000_000L);
        var chain = new IsbnProviderChain(cache, List.of(
                new Counting("openlibrary", new int[]{1}, true)), breaker);

        assertThatThrownBy(() -> chain.lookup("9780306406157"))
                .isInstanceOf(LookupException.class)
                .extracting(e -> ((LookupException) e).status())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
