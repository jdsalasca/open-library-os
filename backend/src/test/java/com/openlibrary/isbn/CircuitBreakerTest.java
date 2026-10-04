package com.openlibrary.isbn;

import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Circuit breaker for the outside ISBN providers.
 *
 * <p>Why it exists: a self-hosted library may sit behind a link where Open Library is
 * unreachable. Without a breaker every ISBN a librarian types waits for the request
 * timeout; with a few queued requests the whole catalogue screen stalls. After a few
 * failures the breaker stops calling, and the chain falls through to the next provider
 * or to the manual form.
 *
 * <p>The breaker rethrows whatever the provider threw, so the provider's own
 * "return empty on failure" rule stays in charge of what the user sees.
 *
 * <p>ponytail: fixed thresholds, no configuration, no metrics, no half-open concurrency
 * limit. Make those configurable when an operator actually asks for them.
 */
class CircuitBreakerTest {

    /** Injectable clock so the cooldown can be tested without sleeping. */
    private static final class FakeClock {
        long nowMillis;

        void advance(Duration d) {
            nowMillis += d.toMillis();
        }
    }

    private static CircuitBreaker breaker(FakeClock clock, int threshold, Duration cooldown) {
        return new CircuitBreaker(threshold, cooldown, () -> clock.nowMillis);
    }

    /** Runs a call that is expected to blow up like an unreachable provider would. */
    private static void expectProviderFailure(CircuitBreaker breaker, String provider) {
        assertThatThrownBy(() -> breaker.call(provider, () -> {
            throw new UncheckedIOException(new java.io.IOException("connection reset"));
        })).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void staysClosedAndForwardsTheCallWhileThingsWork() {
        FakeClock clock = new FakeClock();
        var breaker = breaker(clock, 3, Duration.ofSeconds(30));

        for (int i = 0; i < 5; i++) {
            assertThat(breaker.call("openlibrary", () -> "ok")).isEqualTo("ok");
        }
    }

    @Test
    void opensAfterTheConfiguredNumberOfConsecutiveFailures() {
        FakeClock clock = new FakeClock();
        var breaker = breaker(clock, 3, Duration.ofSeconds(30));

        expectProviderFailure(breaker, "openlibrary");
        expectProviderFailure(breaker, "openlibrary");
        assertThat(breaker.isOpen("openlibrary")).as("two failures is not enough").isFalse();

        expectProviderFailure(breaker, "openlibrary");
        assertThat(breaker.isOpen("openlibrary"))
                .as("the third failure opens the circuit").isTrue();
    }

    @Test
    void doesNotCallTheProviderWhileOpen() {
        FakeClock clock = new FakeClock();
        var breaker = breaker(clock, 2, Duration.ofSeconds(30));
        expectProviderFailure(breaker, "openlibrary");
        expectProviderFailure(breaker, "openlibrary");

        boolean[] called = {false};
        Object result = breaker.call("openlibrary", () -> { called[0] = true; return "late"; });

        assertThat(called[0]).as("an open circuit must not touch the provider").isFalse();
        assertThat(result).as("an open circuit answers with no data").isNull();
    }

    @Test
    void closesAgainOnceTheCooldownHasPassed() {
        FakeClock clock = new FakeClock();
        var breaker = breaker(clock, 2, Duration.ofSeconds(30));
        expectProviderFailure(breaker, "openlibrary");
        expectProviderFailure(breaker, "openlibrary");
        assertThat(breaker.isOpen("openlibrary")).isTrue();

        clock.advance(Duration.ofSeconds(31));

        assertThat(breaker.isOpen("openlibrary")).as("cooldown elapsed").isFalse();
        assertThat(breaker.call("openlibrary", () -> "recovered")).isEqualTo("recovered");
    }

    @Test
    void keepsTheOpenCircuitWhileTheCooldownHasNotElapsed() {
        FakeClock clock = new FakeClock();
        var breaker = breaker(clock, 2, Duration.ofSeconds(30));
        expectProviderFailure(breaker, "openlibrary");
        expectProviderFailure(breaker, "openlibrary");

        clock.advance(Duration.ofSeconds(10));
        assertThat(breaker.isOpen("openlibrary")).isTrue();
    }

    @Test
    void countsFailuresPerProviderSoOneBrokenApiDoesNotBlockTheOther() {
        FakeClock clock = new FakeClock();
        var breaker = breaker(clock, 2, Duration.ofSeconds(30));
        expectProviderFailure(breaker, "openlibrary");
        expectProviderFailure(breaker, "openlibrary");

        assertThat(breaker.isOpen("openlibrary")).isTrue();
        assertThat(breaker.isOpen("googlebooks")).as("a healthy provider stays closed").isFalse();
        assertThat(breaker.call("googlebooks", () -> "fine")).isEqualTo("fine");
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        FakeClock clock = new FakeClock();
        var breaker = breaker(clock, 3, Duration.ofSeconds(30));
        expectProviderFailure(breaker, "openlibrary");
        expectProviderFailure(breaker, "openlibrary");
        assertThat(breaker.call("openlibrary", () -> "ok")).isEqualTo("ok");

        expectProviderFailure(breaker, "openlibrary");
        expectProviderFailure(breaker, "openlibrary");
        assertThat(breaker.isOpen("openlibrary")).as("the counter restarted").isFalse();
    }

    @Test
    void failuresThatAreNotProviderOutagesDoNotTripTheBreaker() {
        FakeClock clock = new FakeClock();
        var breaker = breaker(clock, 2, Duration.ofSeconds(30));

        // A bug in our own code must not be blamed on the outside API.
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> breaker.call("openlibrary", () -> {
                throw new IllegalArgumentException("bad input");
            })).isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(breaker.isOpen("openlibrary")).isFalse();
    }
}
