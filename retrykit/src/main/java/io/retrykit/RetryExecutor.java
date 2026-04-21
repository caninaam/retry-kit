package io.retrykit;

import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class RetryExecutor<T> {

    private final RetryConfig<T> config;
    private String name = "";
    private Consumer<RetryContext> onRetry = ctx -> {};
    private Consumer<T> onSuccess = t -> {};
    private Consumer<Throwable> onFailure = t -> {};
    private FallbackHandler<T> fallback = null;

    private static final Random RANDOM = new Random();

    public RetryExecutor(RetryConfig<T> config) {
        this.config = config;
    }

    public RetryExecutor<T> name(String name) { this.name = name; return this; }
    public RetryExecutor<T> onRetry(Consumer<RetryContext> callback) { this.onRetry = callback; return this; }
    public RetryExecutor<T> onSuccess(Consumer<T> callback) { this.onSuccess = callback; return this; }
    public RetryExecutor<T> onFailure(Consumer<Throwable> callback) { this.onFailure = callback; return this; }
    public RetryExecutor<T> fallback(FallbackHandler<T> handler) { this.fallback = handler; return this; }

    public T call(Callable<T> callable) throws Exception {
        Throwable lastException = null;
        long startMs = System.currentTimeMillis();

        String label = !name.isEmpty() ? name : RetryKitContext.get();
        String tag = label.isEmpty() ? "" : "[" + label + "] ";
        RetryKitLogger.debug(() -> String.format("[RetryKit] %sStarting — maxAttempts=%d backoff=%s",
                tag, config.maxAttempts(), config.backoff().getClass().getSimpleName()));

        long deadline = config.maxDuration()
                .map(d -> startMs + d.toMillis())
                .orElse(Long.MAX_VALUE);

        for (int attempt = 1; attempt <= config.maxAttempts(); attempt++) {
            if (System.currentTimeMillis() >= deadline) {
                RetryKitLogger.warn(String.format("[RetryKit] maxDuration exceeded after %d attempt(s) — aborting",
                        attempt - 1));
                throw new RetryException(attempt - 1, lastException);
            }
            final int a = attempt;
            RetryKitLogger.debug(() -> String.format("[RetryKit] %sAttempt %d/%d", tag, a, config.maxAttempts()));
            try {
                T result = callable.call();

                if (config.retryIf().isPresent() && config.retryIf().get().test(result)) {
                    lastException = new RetryException(attempt, new RuntimeException("retryIf predicate matched"));
                    RetryKitLogger.warn(String.format("[RetryKit] %sAttempt %d/%d — retryIf matched, retrying",
                            tag, attempt, config.maxAttempts()));
                    notifyRetry(attempt, lastException, startMs);
                    sleep(attempt);
                    continue;
                }

                RetryKitLogger.info(String.format("[RetryKit] %sSUCCESS on attempt %d/%d after %dms",
                        tag, attempt, config.maxAttempts(), System.currentTimeMillis() - startMs));
                onSuccess.accept(result);
                return result;

            } catch (Exception e) {
                // CircuitBreakerOpenException is never retried — fail fast immediately
                if (e instanceof CircuitBreakerOpenException) throw e;
                if (!shouldRetryOn(e)) {
                    RetryKitLogger.warn(String.format(
                            "[RetryKit] %sAttempt %d/%d — non-retryable exception: %s — aborting",
                            tag, attempt, config.maxAttempts(), e.getClass().getSimpleName()));
                    throw e;
                }
                lastException = e;
                if (attempt < config.maxAttempts()) {
                    Duration delay = config.backoff().nextDelay(attempt);
                    long ms = delay.toMillis();
                    if (config.jitter() > 0) ms += (long) (ms * config.jitter() * RANDOM.nextDouble());
                    RetryKitLogger.warn(String.format(
                            "[RetryKit] %sAttempt %d/%d FAILED (%s) — retrying in %dms",
                            tag, attempt, config.maxAttempts(), e.getClass().getSimpleName(), ms));
                    notifyRetry(attempt, lastException, startMs);
                    sleepMs(ms);
                } else {
                    RetryKitLogger.warn(String.format(
                            "[RetryKit] %sAttempt %d/%d FAILED (%s) — no more attempts",
                            tag, attempt, config.maxAttempts(), e.getClass().getSimpleName()));
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startMs;
        onFailure.accept(lastException);

        if (fallback != null) {
            RetryKitLogger.info(String.format(
                    "[RetryKit] %sAll %d attempts failed after %dms — activating fallback",
                    tag, config.maxAttempts(), elapsed));
            RetryContext ctx = new RetryContext(config.maxAttempts(), config.maxAttempts(),
                    Optional.ofNullable(lastException), elapsed);
            return fallback.apply(ctx);
        }

        RetryKitLogger.error(String.format(
                "[RetryKit] %sAll %d attempts failed after %dms — throwing RetryException",
                tag, config.maxAttempts(), elapsed));
        throw new RetryException(config.maxAttempts(), lastException);
    }

    public CompletableFuture<T> callAsync(Callable<T> callable) {
        RetryKitLogger.debug("[RetryKit] Starting async execution");
        return CompletableFuture.supplyAsync(() -> {
            try { return call(callable); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    private void notifyRetry(int attempt, Throwable e, long startMs) {
        RetryContext ctx = new RetryContext(attempt, config.maxAttempts(),
                Optional.of(e), System.currentTimeMillis() - startMs);
        onRetry.accept(ctx);
    }

    private void sleep(int attempt) {
        Duration delay = config.backoff().nextDelay(attempt);
        long ms = delay.toMillis();
        if (config.jitter() > 0) ms += (long) (ms * config.jitter() * RANDOM.nextDouble());
        sleepMs(ms);
    }

    private void sleepMs(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private boolean shouldRetryOn(Exception e) {
        return config.retryOn().stream().anyMatch(c -> c.isInstance(e));
    }
}
