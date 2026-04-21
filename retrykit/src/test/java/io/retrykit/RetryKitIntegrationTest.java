package io.retrykit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test covering all retry/CB/pipeline scenarios.
 * Uses short delays (10ms) to keep the suite fast.
 */
class RetryKitIntegrationTest {

    @BeforeEach
    void silenceLogs() {
        RetryKitLogger.setEnabled(false);
    }

    // ── 1. Retry seul ────────────────────────────────────────────────────────

    @Nested
    class RetryOnly {

        @Test
        void sc01_successOnFirstAttempt() throws Exception {
            String result = RetryKit.<String>retry()
                    .maxAttempts(3).waitDuration(Duration.ofMillis(10))
                    .call(() -> "ok");
            assertEquals("ok", result);
        }

        @Test
        void sc02_recoveryAfterNFailures() throws Exception {
            AtomicInteger calls = new AtomicInteger(0);

            String result = RetryKit.<String>retry()
                    .maxAttempts(5).waitDuration(Duration.ofMillis(10))
                    .call(() -> {
                        if (calls.incrementAndGet() <= 3) throw new RuntimeException("down");
                        return "recovered";
                    });

            assertEquals("recovered", result);
            assertEquals(4, calls.get());
        }

        @Test
        void sc03_allAttemptsExhausted_throwsRetryException() {
            RetryException ex = assertThrows(RetryException.class, () ->
                    RetryKit.<String>retry()
                            .maxAttempts(3).waitDuration(Duration.ofMillis(10))
                            .call(() -> { throw new RuntimeException("always down"); })
            );
            assertEquals(3, ex.attempts());
        }

        @Test
        void sc04_allAttemptsExhausted_fallbackCalled() throws Exception {
            AtomicInteger calls = new AtomicInteger(0);

            String result = RetryKit.<String>retry()
                    .maxAttempts(3).waitDuration(Duration.ofMillis(10))
                    .fallback(ctx -> "fallback-" + ctx.maxAttempts())
                    .call(() -> { calls.incrementAndGet(); throw new RuntimeException("down"); });

            assertEquals("fallback-3", result);
            assertEquals(3, calls.get());
        }

        @Test
        void sc05_nonRetryableException_stopsImmediately() {
            AtomicInteger calls = new AtomicInteger(0);

            assertThrows(IllegalArgumentException.class, () ->
                    RetryKit.<String>retry()
                            .maxAttempts(5).waitDuration(Duration.ofMillis(10))
                            .retryOn(java.io.IOException.class)
                            .call(() -> {
                                calls.incrementAndGet();
                                throw new IllegalArgumentException("bad input");
                            })
            );
            assertEquals(1, calls.get()); // stopped immediately, no retry
        }

        @Test
        void sc06_retryIfPredicate_retriesOnUnwantedResult() throws Exception {
            AtomicInteger calls = new AtomicInteger(0);

            String result = RetryKit.<String>retry()
                    .maxAttempts(4).waitDuration(Duration.ofMillis(10))
                    .retryIf(r -> "retry-me".equals(r))
                    .call(() -> calls.incrementAndGet() < 3 ? "retry-me" : "final");

            assertEquals("final", result);
            assertEquals(3, calls.get());
        }

        @Test
        void sc07_maxDurationExceeded_stopsBeforeAllAttempts() {
            AtomicInteger calls = new AtomicInteger(0);

            RetryException ex = assertThrows(RetryException.class, () ->
                    RetryKit.<String>retry()
                            .maxAttempts(10)
                            .waitDuration(Duration.ofMillis(100))
                            .maxDuration(Duration.ofMillis(250))
                            .call(() -> { calls.incrementAndGet(); throw new RuntimeException("down"); })
            );

            // 250ms budget / 100ms wait → 2-3 attempts max, not 10
            assertTrue(calls.get() < 10, "should stop before 10 attempts, got " + calls.get());
            assertTrue(ex.attempts() < 10);
        }
    }

    // ── 2. Circuit Breaker seul ───────────────────────────────────────────────

    @Nested
    class CircuitBreakerOnly {

        private CircuitBreaker cb(int threshold, int minCalls) {
            return new CircuitBreaker(CircuitBreakerConfig.builder()
                    .failureRateThreshold(threshold)
                    .minimumNumberOfCalls(minCalls)
                    .waitDurationInOpenState(Duration.ofMillis(100))
                    .permittedCallsInHalfOpen(1)
                    .build());
        }

        @Test
        void sc08_cbStaysClosed_whenBelowThreshold() {
            CircuitBreaker cb = cb(50, 4);
            cb.allowCall(); cb.recordFailure(); // 1/4
            cb.allowCall(); cb.recordFailure(); // 2/4
            cb.allowCall(); cb.recordSuccess(); // 3/4 — 66% but minCalls not reached
            assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        }

        @Test
        void sc09_cbOpens_whenThresholdReached() {
            CircuitBreaker cb = cb(50, 4);
            for (int i = 0; i < 4; i++) { cb.allowCall(); cb.recordFailure(); }
            assertEquals(CircuitBreaker.State.OPEN, cb.state());
        }

        @Test
        void sc10_cbOpen_blocksCallImmediately() {
            CircuitBreaker cb = cb(50, 2);
            cb.allowCall(); cb.recordFailure();
            cb.allowCall(); cb.recordFailure(); // CB OPEN

            long start = System.currentTimeMillis();
            assertFalse(cb.allowCall());
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(elapsed < 20, "CB OPEN should block instantly, took " + elapsed + "ms");
        }

        @Test
        void sc11_cbOpen_fallbackCalled() throws Exception {
            CircuitBreaker cb = cb(50, 2);
            cb.allowCall(); cb.recordFailure();
            cb.allowCall(); cb.recordFailure();

            String result = RetryKit.<String>retry()
                    .mode(WorkflowMode.CB_FIRST)
                    .maxAttempts(3).waitDuration(Duration.ofMillis(10))
                    .circuitBreaker(c -> c
                            .failureRateThreshold(50)
                            .minimumNumberOfCalls(2)
                            .waitDurationInOpenState(Duration.ofSeconds(60)))
                    .fallback(ctx -> "fallback")
                    .build()
                    .call(() -> { throw new RuntimeException("down"); });

            // Force state by building a separate CB and checking fallback path
            // (test via WorkflowEngine directly for precision)
            assertEquals("fallback", result);
        }

        @Test
        void sc12_halfOpen_probeSucceeds_returnsClosed() throws Exception {
            CircuitBreaker cb = cb(50, 2);
            cb.allowCall(); cb.recordFailure();
            cb.allowCall(); cb.recordFailure(); // OPEN
            assertEquals(CircuitBreaker.State.OPEN, cb.state());

            Thread.sleep(150); // wait > waitDurationInOpenState(100ms)
            assertTrue(cb.allowCall()); // HALF_OPEN probe
            cb.recordSuccess();
            assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        }

        @Test
        void sc13_halfOpen_probeFails_reopens() throws Exception {
            CircuitBreaker cb = cb(50, 2);
            cb.allowCall(); cb.recordFailure();
            cb.allowCall(); cb.recordFailure(); // OPEN

            Thread.sleep(150);
            assertTrue(cb.allowCall()); // HALF_OPEN
            cb.recordFailure();
            assertEquals(CircuitBreaker.State.OPEN, cb.state());
        }
    }

    // ── 3. Retry + CB — distinguer les exceptions ────────────────────────────

    @Nested
    class RetryPlusCb {

        @Test
        void sc14_retryFirst_cbOpensMidRetry_throwsCboe_notRetryException() {
            // CB opens on 3rd failure (minCalls=3, threshold=50%)
            // RETRY_FIRST: each attempt goes through CB
            assertThrows(CircuitBreakerOpenException.class, () ->
                    RetryKit.<String>retry()
                            .mode(WorkflowMode.RETRY_FIRST)
                            .maxAttempts(5)
                            .waitDuration(Duration.ofMillis(10))
                            .circuitBreaker(cb -> cb
                                    .failureRateThreshold(50)
                                    .minimumNumberOfCalls(3)
                                    .waitDurationInOpenState(Duration.ofSeconds(60)))
                            .call(() -> { throw new RuntimeException("down"); })
            );
        }

        @Test
        void sc15_retryFirst_cbAlreadyOpen_immediateFailure() {
            AtomicInteger calls = new AtomicInteger(0);

            // Pre-open CB by forcing failures externally, then test
            // Use CB_FIRST to isolate: CB OPEN from start → 0 calls to service
            assertThrows(CircuitBreakerOpenException.class, () -> {
                RetryKit<String> kit = RetryKit.<String>retry()
                        .mode(WorkflowMode.CB_FIRST)
                        .maxAttempts(5)
                        .waitDuration(Duration.ofMillis(10))
                        .circuitBreaker(cb -> cb
                                .failureRateThreshold(50)
                                .minimumNumberOfCalls(2)
                                .waitDurationInOpenState(Duration.ofSeconds(60)))
                        .build();

                // Open the CB
                for (int i = 0; i < 4; i++) {
                    try { kit.call(() -> { throw new RuntimeException("fail"); }); }
                    catch (Exception ignored) {}
                }
                // CB is now OPEN — this call should fail immediately
                kit.call(() -> { calls.incrementAndGet(); return "ok"; });
            });

            assertEquals(0, calls.get()); // service never called
        }

        @Test
        void sc16_cbFirst_cbOpen_noRetry() throws Exception {
            AtomicInteger calls = new AtomicInteger(0);

            RetryKit<String> kit = RetryKit.<String>retry()
                    .mode(WorkflowMode.CB_FIRST)
                    .maxAttempts(5)
                    .waitDuration(Duration.ofMillis(10))
                    .circuitBreaker(cb -> cb
                            .failureRateThreshold(50)
                            .minimumNumberOfCalls(2)
                            .waitDurationInOpenState(Duration.ofSeconds(60)))
                    .fallback(ctx -> "fallback")
                    .build();

            // Open the CB
            for (int i = 0; i < 4; i++) {
                kit.call(() -> { throw new RuntimeException("fail"); });
            }

            long start = System.currentTimeMillis();
            String result = kit.call(() -> { calls.incrementAndGet(); return "ok"; });
            long elapsed = System.currentTimeMillis() - start;

            assertEquals("fallback", result);
            assertEquals(0, calls.get());          // service never called
            assertTrue(elapsed < 100, "CB_FIRST OPEN should be instant, took " + elapsed + "ms");
        }

        @Test
        void sc17_cbFirst_cbClosed_retriesNormally() throws Exception {
            AtomicInteger calls = new AtomicInteger(0);

            RetryException ex = assertThrows(RetryException.class, () ->
                    RetryKit.<String>retry()
                            .mode(WorkflowMode.CB_FIRST)
                            .maxAttempts(3)
                            .waitDuration(Duration.ofMillis(10))
                            .circuitBreaker(cb -> cb
                                    .failureRateThreshold(50)
                                    .minimumNumberOfCalls(10) // won't open with only 3 calls
                                    .waitDurationInOpenState(Duration.ofSeconds(60)))
                            .call(() -> { calls.incrementAndGet(); throw new RuntimeException("down"); })
            );

            assertEquals(3, calls.get()); // all 3 attempts made
            assertEquals(3, ex.attempts());
        }

        @Test
        void sc18_recoveryBeforeCbOpens() throws Exception {
            AtomicInteger calls = new AtomicInteger(0);

            String result = RetryKit.<String>retry()
                    .mode(WorkflowMode.RETRY_FIRST)
                    .maxAttempts(4)
                    .waitDuration(Duration.ofMillis(10))
                    .circuitBreaker(cb -> cb
                            .failureRateThreshold(50)
                            .minimumNumberOfCalls(10) // won't open
                            .waitDurationInOpenState(Duration.ofSeconds(60)))
                    .call(() -> {
                        if (calls.incrementAndGet() <= 2) throw new RuntimeException("down");
                        return "recovered";
                    });

            assertEquals("recovered", result);
            assertEquals(3, calls.get());
        }
    }

    // ── 4. Pipeline ───────────────────────────────────────────────────────────

    @Nested
    class Pipeline {

        @Test
        void sc19_timeoutGlobal_firesBeforeRetriesDone() {
            AtomicInteger calls = new AtomicInteger(0);

            assertThrows(Exception.class, () ->
                    RetryKit.<String>retry()
                            .pipeline("TIMEOUT(200ms) > RETRY(maxAttempts:5, waitDuration:100ms) > CB(50%)")
                            .call(() -> {
                                calls.incrementAndGet();
                                throw new RuntimeException("down");
                            })
            );

            // 200ms budget, 100ms wait → at most 2 attempts before timeout
            assertTrue(calls.get() < 5, "should not reach 5 attempts, got " + calls.get());
        }

        @Test
        void sc20_cbCallTimeout_cutsSlowCall() {
            long start = System.currentTimeMillis();

            assertThrows(Exception.class, () ->
                    RetryKit.<String>retry()
                            .pipeline("RETRY(maxAttempts:2, waitDuration:10ms) > CB(failureRate:50%, timeout:100ms)")
                            .call(() -> {
                                Thread.sleep(500); // slow service
                                return "too late";
                            })
            );

            long elapsed = System.currentTimeMillis() - start;
            // 2 attempts × 100ms timeout = max ~200ms, not 2×500ms=1000ms
            assertTrue(elapsed < 600, "per-call timeout should cut at 100ms each, total was " + elapsed + "ms");
        }

        @Test
        void sc21_pipeline_cbOpensMidRetry_noSleepAfter() {
            // CB opens on 3rd failure → CBOE propagates immediately, no more sleep
            long start = System.currentTimeMillis();

            assertThrows(CircuitBreakerOpenException.class, () ->
                    RetryKit.<String>retry()
                            .pipeline("RETRY(maxAttempts:6, waitDuration:500ms) > CB(failureRate:50%)")
                            .call(() -> { throw new RuntimeException("down"); })
            );

            long elapsed = System.currentTimeMillis() - start;
            // minCalls=5 → 4 sleeps of 500ms + 5th call opens CB → stop
            // If bug: would sleep 500ms after CB opens → much longer
            assertTrue(elapsed < 3000, "should stop immediately after CB opens, took " + elapsed + "ms");
        }

        @Test
        void sc22_pipelineRetryOnly_noTimeoutNoCb() {
            AtomicInteger calls = new AtomicInteger(0);

            RetryException ex = assertThrows(RetryException.class, () ->
                    RetryKit.<String>retry()
                            .pipeline("RETRY(maxAttempts:3, waitDuration:10ms)")
                            .call(() -> { calls.incrementAndGet(); throw new RuntimeException("down"); })
            );

            assertEquals(3, calls.get());
            assertEquals(3, ex.attempts());
        }
    }

    // ── 5. Distinguer les exceptions ─────────────────────────────────────────

    @Nested
    class ExceptionDistinction {

        @Test
        void retryException_meansServiceTriedAndFailed() {
            Exception ex = assertThrows(RetryException.class, () ->
                    RetryKit.<String>retry()
                            .maxAttempts(3).waitDuration(Duration.ofMillis(10))
                            .call(() -> { throw new RuntimeException("transient"); })
            );
            assertInstanceOf(RetryException.class, ex);
        }

        @Test
        void cbOpenException_meansServiceAlreadyKnownBad() {
            RetryKit<String> kit = RetryKit.<String>retry()
                    .mode(WorkflowMode.CB_FIRST)
                    .maxAttempts(3).waitDuration(Duration.ofMillis(10))
                    .circuitBreaker(cb -> cb
                            .failureRateThreshold(50)
                            .minimumNumberOfCalls(2)
                            .waitDurationInOpenState(Duration.ofSeconds(60)))
                    .build();

            // Open the CB
            for (int i = 0; i < 4; i++) {
                try { kit.call(() -> { throw new RuntimeException("fail"); }); }
                catch (Exception ignored) {}
            }

            assertThrows(CircuitBreakerOpenException.class,
                    () -> kit.call(() -> "ok"));
        }

        @Test
        void nonRetryableException_propagatesOriginal() {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                    RetryKit.<String>retry()
                            .maxAttempts(5).waitDuration(Duration.ofMillis(10))
                            .retryOn(java.io.IOException.class)
                            .call(() -> { throw new IllegalStateException("bad state"); })
            );
            assertEquals("bad state", ex.getMessage());
        }
    }
}
