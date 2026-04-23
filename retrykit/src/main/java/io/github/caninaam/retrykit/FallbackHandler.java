package io.github.caninaam.retrykit;

/**
 * Fallback strategy invoked when all retry attempts fail or the circuit breaker is OPEN.
 *
 * <pre>{@code
 * RetryKit.<String>retry()
 *     .maxAttempts(3)
 *     .fallback(ctx -> "default-value")
 *     .call(() -> myService.call());
 * }</pre>
 *
 * <p>The {@link RetryContext} provides details about the failed execution:
 * <pre>{@code
 * .fallback(ctx -> {
 *     log.warn("All {} attempts failed after {}ms", ctx.maxAttempts(), ctx.elapsedMs());
 *     return cachedValue();
 * })
 * }</pre>
 *
 * @param <T> the return type of the callable
 */
@FunctionalInterface
public interface FallbackHandler<T> {

    /**
     * Provides a fallback value when the callable has failed.
     *
     * @param context context containing attempt count, last exception, and elapsed time
     * @return the fallback value
     * @throws Exception if the fallback itself fails
     */
    T apply(RetryContext context) throws Exception;
}
