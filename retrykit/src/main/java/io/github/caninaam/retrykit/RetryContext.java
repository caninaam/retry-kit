package io.github.caninaam.retrykit;

import java.util.Optional;

/**
 * Context passed to {@link FallbackHandler} and {@code onRetry} callbacks.
 *
 * <pre>{@code
 * RetryKit.<String>retry()
 *     .onRetry(ctx -> log.warn("Attempt {}/{} failed after {}ms — {}",
 *         ctx.attempt(), ctx.maxAttempts(), ctx.elapsedMs(),
 *         ctx.lastException().map(Throwable::getMessage).orElse("")))
 *     .fallback(ctx -> "default")
 *     .build();
 * }</pre>
 *
 * @param attempt       current attempt number (1-based)
 * @param maxAttempts   maximum number of attempts configured
 * @param lastException the exception thrown by the last attempt, if any
 * @param elapsedMs     total elapsed time in milliseconds since the first attempt
 */
public record RetryContext(
        int attempt,
        int maxAttempts,
        Optional<Throwable> lastException,
        long elapsedMs
) {}
