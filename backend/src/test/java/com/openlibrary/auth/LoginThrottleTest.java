package com.openlibrary.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
    private LoginThrottle throttle = new LoginThrottle(Clock.fixed(NOW, ZoneOffset.UTC));
    private Clock laterClock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void letsTheFirstAttemptsThrough() {
        for (int i = 0; i < LoginThrottle.ALLOWED_FAILURES; i++) {
            throttle.recordFailure("email:ana@local");
        }

        // The Nth failure is the one that closes the door, not the one after it.
        assertThat(throttle.isBlocked("email:ana@local")).isTrue();
    }

    @Test
    void blocksOnceTheLimitIsPassed() {
        for (int i = 0; i < LoginThrottle.ALLOWED_FAILURES - 1; i++) {
            throttle.recordFailure("email:ana@local");
        }

        assertThat(throttle.isBlocked("email:ana@local")).isFalse();
    }

    @Test
    void forgetsAKeyOnceTheBlockHasPassed() {
        for (int i = 0; i < LoginThrottle.ALLOWED_FAILURES; i++) {
            throttle.recordFailure("email:ana@local");
        }
        assertThat(throttle.isBlocked("email:ana@local")).isTrue();

        // Sixteen minutes later: nobody may be locked out of their own library.
        laterClock = Clock.fixed(NOW.plus(Duration.ofMinutes(16)), ZoneOffset.UTC);
        throttle = new LoginThrottle(laterClock);

        assertThat(throttle.isBlocked("email:ana@local")).isFalse();
    }

    @Test
    void aGoodPasswordClearsTheCounter() {
        throttle.recordFailure("email:ana@local");
        throttle.recordFailure("email:ana@local");

        throttle.recordSuccess("email:ana@local");

        for (int i = 0; i < LoginThrottle.ALLOWED_FAILURES - 1; i++) {
            throttle.recordFailure("email:ana@local");
        }
        // The window restarted from scratch, so this account is not punished for the
        // typos it made before someone finally got the password right.
        assertThat(throttle.isBlocked("email:ana@local")).isFalse();
    }

    @Test
    void keepsKeysApartSoOneReaderDoesNotLockAnother() {
        for (int i = 0; i < LoginThrottle.ALLOWED_FAILURES; i++) {
            throttle.recordFailure("email:ana@local");
        }

        assertThat(throttle.isBlocked("email:ana@local")).isTrue();
        assertThat(throttle.isBlocked("email:bruno@local")).isFalse();
        assertThat(throttle.isBlocked("ip:10.0.0.1")).isFalse();
    }

    @Test
    void tellsTheClientHowLongToWait() {
        for (int i = 0; i < LoginThrottle.ALLOWED_FAILURES; i++) {
            throttle.recordFailure("email:ana@local");
        }

        var retryAfter = throttle.retryAfter("email:ana@local");

        assertThat(retryAfter).isPositive();
        assertThat(retryAfter).isLessThanOrEqualTo(LoginThrottle.BLOCK.plusSeconds(2));
        assertThat(retryAfter).isGreaterThanOrEqualTo(Duration.ofMinutes(14));
    }

    @Test
    void neverBlocksAKeyThatWasNeverSeen() {
        assertThat(throttle.retryAfter("email:nuevo@local")).isEqualTo(Duration.ZERO);
    }

    @Test
    void countsFailuresPerKeyAndNotInTotal() {
        // Three wrong passwords on three different accounts must not add up to a block.
        throttle.recordFailure("email:a@local");
        throttle.recordFailure("email:b@local");
        throttle.recordFailure("email:c@local");

        assertThat(throttle.isBlocked("email:a@local")).isFalse();
        assertThat(throttle.isBlocked("email:b@local")).isFalse();
        assertThat(throttle.isBlocked("email:c@local")).isFalse();
    }
}
