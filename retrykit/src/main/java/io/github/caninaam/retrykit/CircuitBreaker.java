package io.github.caninaam.retrykit;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final CircuitBreakerConfig config;
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger callCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenCallCount = new AtomicInteger(0);
    private volatile Instant openedAt = null;

    public CircuitBreaker(CircuitBreakerConfig config) {
        this.config = config;
        RetryKitLogger.debug(() -> String.format(
                "[CircuitBreaker] Initialized — threshold=%d%% minCalls=%d waitOpen=%s",
                config.failureRateThreshold(), config.minimumNumberOfCalls(),
                config.waitDurationInOpenState()));
    }

    public java.util.Optional<java.time.Duration> callTimeout() { return config.timeout(); }

    public State state() {
        transitionIfNeeded();
        return state.get();
    }

    public boolean allowCall() {
        transitionIfNeeded();
        State current = state.get();
        boolean allowed = switch (current) {
            case CLOSED    -> true;
            case OPEN      -> false;
            case HALF_OPEN -> halfOpenCallCount.incrementAndGet() <= config.permittedCallsInHalfOpen();
        };
        if (!allowed) RetryKitLogger.warn("[CircuitBreaker] Call BLOCKED — state=" + current);
        return allowed;
    }

    public void recordSuccess() {
        if (state.get() == State.HALF_OPEN) {
            RetryKitLogger.info("[CircuitBreaker] HALF_OPEN → CLOSED (success)");
            reset();
        } else {
            callCount.incrementAndGet();
            RetryKitLogger.debug(() -> String.format("[CircuitBreaker] Success — calls=%d failures=%d",
                    callCount.get(), failureCount.get()));
        }
    }

    public void recordFailure() {
        callCount.incrementAndGet();
        failureCount.incrementAndGet();
        int calls = callCount.get();
        int failures = failureCount.get();

        if (state.get() == State.HALF_OPEN) {
            RetryKitLogger.warn("[CircuitBreaker] HALF_OPEN → OPEN (failure)");
            open();
            return;
        }

        if (calls >= config.minimumNumberOfCalls()) {
            int rate = (failures * 100) / calls;
            if (rate >= config.failureRateThreshold()) {
                RetryKitLogger.warn(String.format(
                        "[CircuitBreaker] CLOSED → OPEN — failure rate %d%% >= threshold %d%%",
                        rate, config.failureRateThreshold()));
                open();
            } else {
                RetryKitLogger.debug(() -> String.format(
                        "[CircuitBreaker] Failure — calls=%d rate=%d%% (below threshold)", calls, rate));
            }
        } else {
            RetryKitLogger.debug(() -> String.format(
                    "[CircuitBreaker] Failure — calls=%d/%d (minimum not reached)",
                    calls, config.minimumNumberOfCalls()));
        }
    }

    private void transitionIfNeeded() {
        if (state.get() == State.OPEN && openedAt != null
                && Instant.now().isAfter(openedAt.plus(config.waitDurationInOpenState()))) {
            if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                halfOpenCallCount.set(0);
                RetryKitLogger.info(String.format(
                        "[CircuitBreaker] OPEN → HALF_OPEN — permitted calls=%d",
                        config.permittedCallsInHalfOpen()));
            }
        }
    }

    private void open() {
        state.set(State.OPEN);
        openedAt = Instant.now();
        RetryKitLogger.warn("[CircuitBreaker] State=OPEN — calls blocked for " + config.waitDurationInOpenState());
    }

    private void reset() {
        state.set(State.CLOSED);
        callCount.set(0);
        failureCount.set(0);
        halfOpenCallCount.set(0);
        openedAt = null;
        RetryKitLogger.info("[CircuitBreaker] State=CLOSED — circuit reset");
    }
}
