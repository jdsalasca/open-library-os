package com.openlibrary.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Throttles password guessing per email and per client address.
 *
 * <p>Deliberately in memory and deliberately temporary: this is a speed bump against
 * a script trying thousands of passwords, not a substitute for the password hash.
 * A restart clears it, which is the right trade for a self-hosted single-node app —
 * and the accounts themselves carry no lockout state to get stuck in.
 *
 * <p>ponytail: fixed window per key, no buckets and no smoothing. If a burst of real
 * librarians ever trips it, widen the window rather than building a rate limiter.
 */
@Component
public class LoginThrottle {

    /**
     * Wrong passwords allowed per account before the door closes for that account.
     */
    static final int ALLOWED_FAILURES = 5;

    /**
     * Wrong passwords allowed per address. Much higher on purpose: everyone behind
     * one router or one reverse proxy shares an address, so a small limit here
     * would lock out an entire library the first time somebody fat-fingered a
     * password. This only catches a script spraying many accounts.
     */
    static final int ALLOWED_FAILURES_PER_ADDRESS = 50;

    static final java.time.Duration WINDOW = java.time.Duration.ofMinutes(15);
    static final java.time.Duration BLOCK = java.time.Duration.ofMinutes(15);

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    /** The clock is injected so the block can actually be tested expiring. */
    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    private record Attempts(int failures, Instant firstFailure, Instant blockedUntil) {
    }

    /** How long this key must wait, or zero when it may try. */
    public java.time.Duration retryAfter(String key) {
        var entry = attempts.get(key);
        if (entry == null || entry.blockedUntil() == null) {
            return java.time.Duration.ZERO;
        }
        var left = java.time.Duration.between(clock.instant(), entry.blockedUntil());
        return left.isNegative() ? java.time.Duration.ZERO : left;
    }

    /** Called before checking the password: a blocked key never reaches the hash. */
    public boolean isBlocked(String key) {
        return !retryAfter(key).isZero();
    }

    public void recordFailure(String key, int allowedFailures) {
        attempts.compute(key, (ignored, entry) -> {
            var now = clock.instant();
            if (entry == null || entry.firstFailure().plus(WINDOW).isBefore(now)) {
                // New window.
                return new Attempts(1, now, null);
            }
            int failures = entry.failures() + 1;
            var blockedUntil = failures >= allowedFailures ? now.plus(BLOCK) : null;
            return new Attempts(failures, entry.firstFailure(), blockedUntil);
        });
    }

    public void recordFailure(String key) {
        recordFailure(key, ALLOWED_FAILURES);
    }

    public void recordSuccess(String key) {
        attempts.remove(key);
    }
}
