package io.retrykit;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class PipelineExecutor<T> {

    private static final Logger LOG = Logger.getLogger(PipelineExecutor.class.getName());

    // CbWrapper holds a live, stateful CircuitBreaker for each CB step
    private record CbWrapper(PipelineStep.CbStep config, CircuitBreaker cb) {}

    private final List<Object> chain; // CbWrapper | RetryStep | TimeoutStep
    private final Optional<PipelineStep.FallbackStep> fallbackStep;

    public Optional<CircuitBreaker.State> circuitBreakerState() {
        return chain.stream()
                .filter(s -> s instanceof CbWrapper)
                .map(s -> ((CbWrapper) s).cb().state())
                .findFirst();
    }

    public PipelineExecutor(List<PipelineStep> steps) {
        List<Object> chain = new ArrayList<>();
        PipelineStep.FallbackStep fb = null;

        String dsl = steps.stream().map(s -> s.getClass().getSimpleName()).collect(Collectors.joining(" > "));
        LOG.fine("[Pipeline] Building chain: " + dsl);

        for (PipelineStep step : steps) {
            if (step instanceof PipelineStep.FallbackStep fs) {
                fb = fs;
            } else if (step instanceof PipelineStep.CbStep cs) {
                CircuitBreakerConfig.Builder cbBuilder = CircuitBreakerConfig.builder()
                        .failureRateThreshold(cs.failureRateThreshold())
                        .waitDurationInOpenState(cs.waitDurationOpen());
                cs.timeout().ifPresent(cbBuilder::timeout);
                chain.add(new CbWrapper(cs, new CircuitBreaker(cbBuilder.build())));
            } else {
                chain.add(step);
            }
        }

        this.chain = chain;
        this.fallbackStep = Optional.ofNullable(fb);
    }

    public T execute(Callable<T> callable, FallbackHandler<T> globalFallback) throws Exception {
        // Build decorator chain right-to-left: leftmost step is outermost
        Callable<T> current = callable;
        for (int i = chain.size() - 1; i >= 0; i--) {
            current = wrap(chain.get(i), current);
        }

        try {
            T result = current.call();
            LOG.info("[Pipeline] Execution SUCCESS");
            return result;
        } catch (Exception e) {
            if (globalFallback != null) {
                LOG.warning("[Pipeline] All steps failed (" + e.getClass().getSimpleName() + ") — activating fallback");
                return globalFallback.apply(new RetryContext(0, 0, Optional.of(e), 0));
            }
            LOG.severe("[Pipeline] All steps failed — no fallback configured");
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private Callable<T> wrap(Object item, Callable<T> inner) {
        if (item instanceof CbWrapper cw) {
            return wrapCb(cw.cb(), inner);
        } else if (item instanceof PipelineStep.RetryStep rs) {
            return wrapRetry(rs, inner);
        } else if (item instanceof PipelineStep.TimeoutStep ts) {
            return wrapTimeout(ts.duration(), inner);
        }
        return inner;
    }

    private Callable<T> wrapCb(CircuitBreaker cb, Callable<T> inner) {
        return () -> {
            if (!cb.allowCall()) throw new CircuitBreakerOpenException(cb.state());
            try {
                T result = cb.callTimeout().isPresent()
                        ? callWithTimeout(inner, cb.callTimeout().get())
                        : inner.call();
                cb.recordSuccess();
                return result;
            } catch (Exception e) {
                cb.recordFailure();
                if (cb.state() == CircuitBreaker.State.OPEN) throw new CircuitBreakerOpenException(cb.state());
                throw e;
            }
        };
    }

    private T callWithTimeout(Callable<T> callable, Duration timeout) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> future = executor.submit(callable);
        executor.shutdown();
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new java.util.concurrent.TimeoutException("CB call timed out after " + timeout);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }

    private Callable<T> wrapRetry(PipelineStep.RetryStep rs, Callable<T> inner) {
        return () -> {
            Throwable last = null;
            for (int attempt = 1; attempt <= rs.maxAttempts(); attempt++) {
                try {
                    return inner.call();
                } catch (CircuitBreakerOpenException e) {
                    throw e; // CB is OPEN — fail immediately, no retry
                } catch (Exception e) {
                    last = e;
                    if (attempt < rs.maxAttempts()) {
                        long delay = rs.backoff().nextDelay(attempt).toMillis();
                        if (rs.jitter() > 0) delay += (long) (delay * rs.jitter() * Math.random());
                        Thread.sleep(delay);
                    }
                }
            }
            throw new RetryException(rs.maxAttempts(), last);
        };
    }

    private Callable<T> wrapTimeout(java.time.Duration duration, Callable<T> inner) {
        LOG.fine("[Pipeline] Wrapping with TIMEOUT(" + duration + ")");
        return () -> {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<T> future = executor.submit(inner);
            executor.shutdown();
            try {
                return future.get(duration.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new java.util.concurrent.TimeoutException("Timed out after " + duration);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Exception ex) throw ex;
                throw e;
            }
        };
    }
}
