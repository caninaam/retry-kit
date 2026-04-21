package io.github.caninaam.retrykit;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.*;

public class WorkflowEngine<T> {

    private final WorkflowMode mode;
    private final RetryExecutor<T> retryExecutor;
    private final Optional<CircuitBreaker> circuitBreaker;
    private final Optional<PipelineExecutor<T>> pipelineExecutor;

    public WorkflowEngine(WorkflowMode mode, RetryExecutor<T> retryExecutor,
                          Optional<CircuitBreaker> circuitBreaker) {
        this.mode = mode;
        this.retryExecutor = retryExecutor;
        this.circuitBreaker = circuitBreaker;
        this.pipelineExecutor = Optional.empty();
    }

    public WorkflowEngine(PipelineExecutor<T> pipelineExecutor) {
        this.mode = WorkflowMode.PIPELINE;
        this.retryExecutor = null;
        this.circuitBreaker = Optional.empty();
        this.pipelineExecutor = Optional.of(pipelineExecutor);
    }

    public Optional<CircuitBreaker.State> circuitBreakerState() {
        if (mode == WorkflowMode.PIPELINE)
            return pipelineExecutor.flatMap(PipelineExecutor::circuitBreakerState);
        return circuitBreaker.map(CircuitBreaker::state);
    }

    public T execute(Callable<T> callable, FallbackHandler<T> fallback) throws Exception {
        return switch (mode) {
            case RETRY_FIRST -> executeRetryFirst(callable, fallback);
            case CB_FIRST    -> executeCbFirst(callable, fallback);
            case PIPELINE    -> pipelineExecutor.get().execute(callable, fallback);
        };
    }

    // Retry is outer: each attempt goes through CB + optional per-call timeout
    private T executeRetryFirst(Callable<T> callable, FallbackHandler<T> fallback) throws Exception {
        try {
            return retryExecutor.call(() -> {
                if (circuitBreaker.isPresent()) {
                    CircuitBreaker cb = circuitBreaker.get();
                    if (!cb.allowCall()) throw new CircuitBreakerOpenException(cb.state());
                    try {
                        T result = callWithTimeout(callable, cb.callTimeout());
                        cb.recordSuccess();
                        return result;
                    } catch (Exception e) {
                        cb.recordFailure();
                        throw e;
                    }
                }
                return callable.call();
            });
        } catch (CircuitBreakerOpenException e) {
            // CB is OPEN — fail immediately, no wait
            return failFast(e, fallback);
        } catch (RetryException e) {
            if (fallback != null) {
                return fallback.apply(new RetryContext(
                        e.attempts(), e.attempts(), Optional.ofNullable(e.getCause()), 0));
            }
            throw e;
        }
    }

    // CB is outer: if OPEN, no retry at all
    private T executeCbFirst(Callable<T> callable, FallbackHandler<T> fallback) throws Exception {
        if (circuitBreaker.isPresent()) {
            CircuitBreaker cb = circuitBreaker.get();
            if (!cb.allowCall()) return failFast(new CircuitBreakerOpenException(cb.state()), fallback);
            try {
                T result = retryExecutor.call(
                        () -> callWithTimeout(callable, cb.callTimeout()));
                cb.recordSuccess();
                return result;
            } catch (RetryException e) {
                cb.recordFailure();
                if (fallback != null) {
                    return fallback.apply(new RetryContext(
                            e.attempts(), e.attempts(), Optional.ofNullable(e.getCause()), 0));
                }
                throw e;
            } catch (Exception e) {
                cb.recordFailure();
                throw e;
            }
        }
        try {
            return retryExecutor.call(callable);
        } catch (RetryException e) {
            if (fallback != null) {
                return fallback.apply(new RetryContext(
                        e.attempts(), e.attempts(), Optional.ofNullable(e.getCause()), 0));
            }
            throw e;
        }
    }

    private T failFast(CircuitBreakerOpenException e, FallbackHandler<T> fallback) throws Exception {
        if (fallback != null) {
            return fallback.apply(new RetryContext(0, 0, Optional.of(e), 0));
        }
        throw e;
    }

    private T callWithTimeout(Callable<T> callable, Optional<Duration> timeout) throws Exception {
        if (timeout.isEmpty()) return callable.call();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> future = executor.submit(callable);
        executor.shutdown();
        try {
            return future.get(timeout.get().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new java.util.concurrent.TimeoutException("Call timed out after " + timeout.get());
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }
}
