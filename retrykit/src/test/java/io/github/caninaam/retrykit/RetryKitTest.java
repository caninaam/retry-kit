package io.github.caninaam.retrykit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RetryKitTest {

    @Test
    void successOnFirstAttempt() throws Exception {
        String result = RetryKit.<String>retry()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .call(() -> "ok");

        assertEquals("ok", result);
    }

    @Test
    void retriesAndSucceedsOnThirdAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);

        String result = RetryKit.<String>retry()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .call(() -> {
                    if (calls.incrementAndGet() < 3) throw new RuntimeException("not yet");
                    return "ok";
                });

        assertEquals("ok", result);
        assertEquals(3, calls.get());
    }

    @Test
    void fallbackCalledWhenAllAttemptsFail() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);

        String result = RetryKit.<String>retry()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .fallback(ctx -> "fallback")
                .call(() -> {
                    calls.incrementAndGet();
                    throw new RuntimeException("always fails");
                });

        assertEquals("fallback", result);
        assertEquals(3, calls.get());
    }

    @Test
    void throwsRetryExceptionWhenNoFallback() {
        assertThrows(RetryException.class, () ->
                RetryKit.<String>retry()
                        .maxAttempts(2)
                        .waitDuration(Duration.ofMillis(10))
                        .call(() -> { throw new RuntimeException("fail"); })
        );
    }

    @Test
    void onRetryCallbackFiredForEachRetry() throws Exception {
        AtomicInteger retryCalls = new AtomicInteger(0);
        AtomicInteger calls = new AtomicInteger(0);

        RetryKit.<String>retry()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .onRetry(ctx -> retryCalls.incrementAndGet())
                .call(() -> {
                    if (calls.incrementAndGet() < 3) throw new RuntimeException("fail");
                    return "ok";
                });

        assertEquals(2, retryCalls.get());
    }
}
