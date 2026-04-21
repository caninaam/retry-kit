package io.retrykit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    private CircuitBreaker cb(int threshold, int minCalls, Duration waitDuration) {
        return new CircuitBreaker(CircuitBreakerConfig.builder()
                .failureRateThreshold(threshold)
                .minimumNumberOfCalls(minCalls)
                .waitDurationInOpenState(waitDuration)
                .slidingWindowSize(10)
                .build());
    }

    @Test
    void initialStateIsClosed() {
        CircuitBreaker cb = cb(50, 5, Duration.ofSeconds(10));
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.allowCall());
    }

    @Test
    void doesNotOpenBeforeMinimumCalls() {
        CircuitBreaker cb = cb(50, 5, Duration.ofSeconds(10));
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        // 3 failures but minimumNumberOfCalls = 5 → still CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    void opensAfterFailureThreshold() {
        CircuitBreaker cb = cb(50, 5, Duration.ofSeconds(10));
        // 5 failures = 100% → above 50% threshold
        for (int i = 0; i < 5; i++) cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
    }

    @Test
    void doesNotOpenWhenFailureRateBelowThreshold() {
        CircuitBreaker cb = cb(50, 4, Duration.ofSeconds(10));
        // 1 failure + 3 successes = 25% < 50%
        cb.recordFailure();
        cb.recordSuccess();
        cb.recordSuccess();
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    void rejectsCallsWhenOpen() {
        CircuitBreaker cb = cb(50, 5, Duration.ofSeconds(10));
        for (int i = 0; i < 5; i++) cb.recordFailure();

        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.allowCall());
    }

    @Test
    void transitionsToHalfOpenAfterWait() throws InterruptedException {
        CircuitBreaker cb = cb(50, 5, Duration.ofMillis(100));
        for (int i = 0; i < 5; i++) cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());

        Thread.sleep(150);

        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
        assertTrue(cb.allowCall());
    }

    @Test
    void closesAfterSuccessfulHalfOpenCalls() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(CircuitBreakerConfig.builder()
                .failureRateThreshold(50).minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofMillis(100))
                .permittedCallsInHalfOpen(2).build());

        for (int i = 0; i < 5; i++) cb.recordFailure();
        Thread.sleep(150);

        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
        cb.recordSuccess(); // triggers reset → CLOSED
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    void reopensAfterFailureInHalfOpen() throws InterruptedException {
        CircuitBreaker cb = cb(50, 5, Duration.ofMillis(100));
        for (int i = 0; i < 5; i++) cb.recordFailure();
        Thread.sleep(150);

        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
        cb.recordFailure(); // back to OPEN
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
    }
}
