package io.github.caninaam.retrykit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowEngineTest {

    // ── RETRY_FIRST ───────────────────────────────────────────────────────────

    @Test
    void retryFirst_succeedsAfterRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);

        String result = engine(WorkflowMode.RETRY_FIRST, 3, Optional.empty())
                .execute(() -> {
                    if (calls.incrementAndGet() < 3) throw new RuntimeException("not yet");
                    return "ok";
                }, null);

        assertEquals("ok", result);
        assertEquals(3, calls.get());
    }

    @Test
    void retryFirst_eachRetrySeenByCircuitBreaker() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        CircuitBreaker cb = circuitBreaker(50, 10, Duration.ofSeconds(10)); // won't open in this test

        String result = engine(WorkflowMode.RETRY_FIRST, 3, Optional.of(cb))
                .execute(() -> {
                    if (calls.incrementAndGet() < 3) throw new RuntimeException("fail");
                    return "ok";
                }, null);

        assertEquals("ok", result);
        // 2 failures recorded by CB (attempts 1 and 2 failed)
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }

    @Test
    void retryFirst_fallbackWhenAllAttemptsFail() throws Exception {
        String result = engine(WorkflowMode.RETRY_FIRST, 3, Optional.empty())
                .execute(() -> { throw new RuntimeException("always fails"); },
                        ctx -> "fallback");

        assertEquals("fallback", result);
    }

    // ── CB_FIRST ──────────────────────────────────────────────────────────────

    @Test
    void cbFirst_blocksWhenOpen() throws Exception {
        CircuitBreaker cb = circuitBreaker(50, 5, Duration.ofSeconds(10));
        // Force open
        for (int i = 0; i < 5; i++) cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());

        WorkflowEngine<String> engine = engine(WorkflowMode.CB_FIRST, 3, Optional.of(cb));

        assertThrows(CircuitBreakerOpenException.class,
                () -> engine.execute(() -> "should not reach", null));
    }

    @Test
    void cbFirst_noRetryWhenCbOpen() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        CircuitBreaker cb = circuitBreaker(50, 5, Duration.ofSeconds(10));
        for (int i = 0; i < 5; i++) cb.recordFailure();

        WorkflowEngine<String> engine = engine(WorkflowMode.CB_FIRST, 3, Optional.of(cb));

        assertThrows(CircuitBreakerOpenException.class,
                () -> engine.execute(() -> { calls.incrementAndGet(); return "ok"; }, null));

        // CB blocked call — service was never called
        assertEquals(0, calls.get());
    }

    // ── PIPELINE ──────────────────────────────────────────────────────────────

    @Test
    void pipeline_retryThenCb() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);

        // RETRY(3) > CB(50%)
        PipelineExecutor<String> pe = new PipelineExecutor<>(
                PipelineDslParser.parse("RETRY(3) > CB(50%)")
        );
        WorkflowEngine<String> engine = new WorkflowEngine<>(pe);

        String result = engine.execute(() -> {
            if (calls.incrementAndGet() < 3) throw new RuntimeException("not yet");
            return "ok";
        }, null);

        assertEquals("ok", result);
        assertEquals(3, calls.get());
    }

    @Test
    void pipeline_fallbackAfterAllFail() throws Exception {
        PipelineExecutor<String> pe = new PipelineExecutor<>(
                PipelineDslParser.parse("RETRY(2) > FALLBACK(value:cached)")
        );
        WorkflowEngine<String> engine = new WorkflowEngine<>(pe);

        String result = engine.execute(
                () -> { throw new RuntimeException("down"); },
                ctx -> "global-fallback");

        assertEquals("global-fallback", result);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private WorkflowEngine<String> engine(WorkflowMode mode, int maxAttempts, Optional<CircuitBreaker> cb) {
        RetryConfig<String> config = RetryConfig.<String>builder()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofMillis(10))
                .build();
        RetryExecutor<String> executor = new RetryExecutor<>(config);
        return new WorkflowEngine<>(mode, executor, cb);
    }

    private CircuitBreaker circuitBreaker(int threshold, int minCalls, Duration wait) {
        return new CircuitBreaker(CircuitBreakerConfig.builder()
                .failureRateThreshold(threshold)
                .minimumNumberOfCalls(minCalls)
                .waitDurationInOpenState(wait)
                .build());
    }
}
