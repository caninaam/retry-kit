package io.github.caninaam.retrykit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RetryExecutorTest {

    @Test
    void fixedDelayBetweenAttempts() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        long start = System.currentTimeMillis();

        RetryConfig<String> config = RetryConfig.<String>builder()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(100))
                .build();

        new RetryExecutor<>(config).call(() -> {
            if (calls.incrementAndGet() < 3) throw new RuntimeException("fail");
            return "ok";
        });

        long elapsed = System.currentTimeMillis() - start;
        // 2 delays of 100ms each → at least 200ms
        assertTrue(elapsed >= 180, "Expected at least 180ms, got " + elapsed);
    }

    @Test
    void exponentialBackoffGrowsDelay() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        long[] timestamps = new long[3];

        RetryConfig<String> config = RetryConfig.<String>builder()
                .maxAttempts(3)
                .exponentialBackoff(Duration.ofMillis(50), 2.0)
                .build();

        new RetryExecutor<>(config).call(() -> {
            int i = calls.getAndIncrement();
            timestamps[i] = System.currentTimeMillis();
            if (i < 2) throw new RuntimeException("fail");
            return "ok";
        });

        long firstDelay = timestamps[1] - timestamps[0];
        long secondDelay = timestamps[2] - timestamps[1];
        // second delay should be roughly 2x the first
        assertTrue(secondDelay >= firstDelay, "Second delay should be >= first delay");
    }

    @Test
    void onRetryCallbackReceivesAttemptNumber() throws Exception {
        AtomicInteger lastAttempt = new AtomicInteger(0);
        AtomicInteger calls = new AtomicInteger(0);

        RetryConfig<String> config = RetryConfig.<String>builder()
                .maxAttempts(3).waitDuration(Duration.ofMillis(10)).build();

        new RetryExecutor<String>(config)
                .onRetry(ctx -> lastAttempt.set(ctx.attempt()))
                .call(() -> {
                    if (calls.incrementAndGet() < 3) throw new RuntimeException("fail");
                    return "ok";
                });

        assertEquals(2, lastAttempt.get()); // retried on attempt 1 and 2
    }

    @Test
    void onSuccessCallbackFired() throws Exception {
        AtomicReference<String> received = new AtomicReference<>();

        RetryConfig<String> config = RetryConfig.<String>builder()
                .maxAttempts(1).waitDuration(Duration.ofMillis(10)).build();

        new RetryExecutor<String>(config)
                .onSuccess(received::set)
                .call(() -> "done");

        assertEquals("done", received.get());
    }

    @Test
    void onFailureCallbackFiredAfterAllAttempts() throws Exception {
        AtomicReference<Throwable> received = new AtomicReference<>();

        RetryConfig<String> config = RetryConfig.<String>builder()
                .maxAttempts(2).waitDuration(Duration.ofMillis(10)).build();

        new RetryExecutor<String>(config)
                .onFailure(received::set)
                .fallback(ctx -> "fallback")
                .call(() -> { throw new RuntimeException("boom"); });

        assertNotNull(received.get());
        assertEquals("boom", received.get().getMessage());
    }

    @Test
    void retryIfPredicateTriggersRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);

        RetryConfig<Integer> config = RetryConfig.<Integer>builder()
                .maxAttempts(3).waitDuration(Duration.ofMillis(10))
                .retryIf(v -> v < 0) // retry if result is negative
                .build();

        int result = new RetryExecutor<>(config).call(() -> {
            int n = calls.incrementAndGet();
            return n < 3 ? -1 : 42; // returns negative until 3rd call
        });

        assertEquals(42, result);
        assertEquals(3, calls.get());
    }

    @Test
    void doesNotRetryOnUnexpectedException() {
        RetryConfig<String> config = RetryConfig.<String>builder()
                .maxAttempts(3).waitDuration(Duration.ofMillis(10))
                .retryOn(java.io.IOException.class) // only retry IOExceptions
                .build();

        AtomicInteger calls = new AtomicInteger(0);

        assertThrows(RuntimeException.class, () ->
                new RetryExecutor<String>(config).call(() -> {
                    calls.incrementAndGet();
                    throw new RuntimeException("not retryable");
                })
        );

        assertEquals(1, calls.get()); // no retry
    }

    @Test
    void asyncReturnsCompletableFuture() throws Exception {
        RetryConfig<String> config = RetryConfig.<String>builder()
                .maxAttempts(1).waitDuration(Duration.ofMillis(10)).build();

        String result = new RetryExecutor<String>(config)
                .callAsync(() -> "async-ok")
                .get();

        assertEquals("async-ok", result);
    }
}
