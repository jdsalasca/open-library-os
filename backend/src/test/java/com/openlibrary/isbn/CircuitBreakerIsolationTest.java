package com.openlibrary.isbn;

import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Isolation: does the breaker count the exception the chain actually produces? */
class CircuitBreakerIsolationTest {

    @Test
    void countsUncheckedIOExceptionAsAProviderFault() {
        var breaker = new CircuitBreaker(2, Duration.ofMinutes(5), () -> 1_700_000_000_000L);

        assertThatThrownBy(() -> breaker.call("openlibrary", () -> {
            throw new UncheckedIOException(new java.io.IOException("unreachable"));
        })).isInstanceOf(UncheckedIOException.class);

        assertThatThrownBy(() -> breaker.call("openlibrary", () -> {
            throw new UncheckedIOException(new java.io.IOException("unreachable"));
        })).isInstanceOf(UncheckedIOException.class);

        assertThat(breaker.isOpen("openlibrary"))
                .as("two UncheckedIOExceptions must trip a threshold of two").isTrue();
    }

    @Test
    void countsRestClientExceptionAsAProviderFault() {
        var breaker = new CircuitBreaker(1, Duration.ofMinutes(5), () -> 1_700_000_000_000L);

        assertThatThrownBy(() -> breaker.call("openlibrary", () -> {
            throw new org.springframework.web.client.ResourceAccessException(
                    "timeout", new java.io.IOException("read timed out"));
        })).isInstanceOf(org.springframework.web.client.ResourceAccessException.class);

        assertThat(breaker.isOpen("openlibrary")).isTrue();
    }

    @Test
    void returnsNullWhenOpenSoCallersKnowNothingWasAttempted() {
        var breaker = new CircuitBreaker(1, Duration.ofMinutes(5), () -> 1_700_000_000_000L);

        assertThatThrownBy(() -> breaker.call("openlibrary", () -> {
            throw new UncheckedIOException(new java.io.IOException("gone"));
        })).isInstanceOf(UncheckedIOException.class);
        assertThat(breaker.isOpen("openlibrary")).isTrue();

        // Documented contract: an open circuit yields null rather than calling through.
        // IsbnProviderChain turns that null into an empty Optional.
        assertThat(breaker.call("openlibrary", () -> "never")).isNull();
    }
}
