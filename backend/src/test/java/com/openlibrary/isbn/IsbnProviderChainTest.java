package com.openlibrary.isbn;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The provider chain: Open Library first, Google Books second, cache in front of both.
 *
 * <p>Plain JUnit with hand-rolled doubles on purpose. The chain is a few lines of
 * "try the next one", and a Spring context plus a database would only slow it down.
 */
class IsbnProviderChainTest {

    /** Provider double that returns whatever the test tells it to. */
    private static final class Fake implements IsbnProvider {
        private final String name;
        private final Optional<ExternalBook> answer;

        Fake(String name, Optional<ExternalBook> answer) {
            this.name = name;
            this.answer = answer;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Optional<ExternalBook> lookup(Isbn isbn) {
            return answer;
        }
    }

    /** In-memory stand-in for the cache, so the test needs no database. */
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

    private static ExternalBook book(String title) {
        return new ExternalBook.Mutable("9780306406157", title, null, List.of("William Gibson"),
                "Ace", 1984, List.of(), "en", 271, null, null).toRecord();
    }

    @Test
    void stopsAtTheFirstProviderThatKnowsTheBook() {
        FakeCache cache = new FakeCache();
        var chain = new IsbnProviderChain(cache, List.of(
                new Fake("openlibrary", Optional.of(book("From Open Library"))),
                new Fake("googlebooks", Optional.of(book("From Google Books")))));

        ExternalBook found = chain.lookup("9780306406157");

        assertThat(found.title()).isEqualTo("From Open Library");
        assertThat(cache.sources.get("9780306406157")).isEqualTo("openlibrary");
    }

    @Test
    void fallsBackToTheNextProviderWhenTheFirstHasNothing() {
        FakeCache cache = new FakeCache();
        var chain = new IsbnProviderChain(cache, List.of(
                new Fake("openlibrary", Optional.empty()),
                new Fake("googlebooks", Optional.of(book("From Google Books")))));

        ExternalBook found = chain.lookup("9780306406157");

        assertThat(found.title()).isEqualTo("From Google Books");
        assertThat(cache.sources.get("9780306406157")).isEqualTo("googlebooks");
    }

    @Test
    void aCachedAnswerIsServedWithoutAskingAnyProvider() {
        FakeCache cache = new FakeCache();
        cache.put("9780306406157", "openlibrary", book("Cached"));
        var chain = new IsbnProviderChain(cache, List.of(
                new Fake("openlibrary", Optional.of(book("Fresh")))));

        assertThat(chain.lookup("9780306406157").title()).isEqualTo("Cached");
    }

    @Test
    void rejectsAnInvalidIsbnBeforeReachingAnyProvider() {
        var chain = new IsbnProviderChain(new FakeCache(), List.of(
                new Fake("openlibrary", Optional.of(book("X")))));

        assertThatThrownBy(() -> chain.lookup("9780306406158"))
                .isInstanceOf(LookupException.class)
                .extracting(e -> ((LookupException) e).status())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void reportsNotFoundOnlyAfterEveryProviderHasBeenTried() {
        var chain = new IsbnProviderChain(new FakeCache(), List.of(
                new Fake("openlibrary", Optional.empty()),
                new Fake("googlebooks", Optional.empty())));

        assertThatThrownBy(() -> chain.lookup("9780306406157"))
                .isInstanceOf(LookupException.class)
                .extracting(e -> ((LookupException) e).code())
                .isEqualTo("not_found");
    }

    @Test
    void worksEvenWhenNoProviderIsConfigured() {
        var chain = new IsbnProviderChain(new FakeCache(), List.of());

        assertThatThrownBy(() -> chain.lookup("9780306406157"))
                .isInstanceOf(LookupException.class);
    }
}
