package com.openlibrary.isbn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Stops calling a provider that keeps failing.
 *
 * <p>A self-hosted library may sit behind a link where the outside APIs are
 * unreachable. Without this, every ISBN typed in the catalogue waits for the request
 * timeout; with several requests queued, the screen stalls. Once the circuit opens the
 * provider is skipped and the answer is "no data" immediately, so the chain falls
 * through to the next provider or to the manual form.
 *
 * <p>State is per provider: one broken API must not block the other.
 *
 * <p>ponytail: fixed thresholds and a single-threaded half-open transition. No config,
 * no metrics, no concurrency limiter on the probe. Add them when an operator asks.
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final int failureThreshold;
    private final Duration cooldown;
    private final LongSupplier clock;

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public CircuitBreaker(int failureThreshold, Duration cooldown, LongSupplier clock) {
        this.failureThreshold = failureThreshold;
        this.cooldown = cooldown;
        this.clock = clock;
    }

    /**
     * Runs {@code call} unless this provider's circuit is open, in which case it returns
     * null without touching the network.
     *
     * <p>Only failures that look like the provider being unavailable count towards
     * opening: a bug in our own code must not be blamed on the outside API.
     */
    public <T> T call(String provider, Supplier<T> call) {
        State state = stateFor(provider);
        if (isTripped(state)) {
            return null;
        }

        try {
            T result = call.get();
            state.consecutiveFailures.set(0);
            return result;
        } catch (RuntimeException e) {
            if (isProviderFault(e)) {
                if (state.consecutiveFailures.incrementAndGet() >= failureThreshold) {
                    state.openedAtMillis.set(clock.getAsLong());
                    log.warn("circuit opened for {} after {} consecutive failures",
                            provider, failureThreshold);
                }
            }
            throw e;
        }
    }

    public boolean isOpen(String provider) {
        return isTripped(stateFor(provider));
    }

    private boolean isTripped(State state) {
        long openedAt = state.openedAtMillis.get();
        // Long.MIN_VALUE, not 0: a breaker that opened at the very first tick must not
        // look like one that never opened.
        if (openedAt == Long.MIN_VALUE) {
            return false;
        }
        if (clock.getAsLong() - openedAt < cooldown.toMillis()) {
            return true;
        }
        // Cooldown elapsed: half-open. Let the next call through, which closes the
        // circuit on success or re-opens it on failure.
        state.openedAtMillis.compareAndSet(openedAt, Long.MIN_VALUE);
        return false;
    }

    private State stateFor(String provider) {
        return states.computeIfAbsent(provider, name -> new State());
    }

    /**
     * IOException and friends mean the provider is unreachable or timed out; anything
     * else (a null ISBN, a bug) is ours and must not affect the breaker.
     */
    private static boolean isProviderFault(RuntimeException e) {
        // The chain wraps any provider error in this type precisely so a failure that
        // came from outside is not mistaken for a bug in our own code.
        if (e instanceof IsbnProviderChain.ProviderUnavailable) {
            return true;
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.IOException
                    || cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof org.springframework.web.client.RestClientException) {
                return true;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }

    private static final class State {
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicLong openedAtMillis =
                new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
    }
}
