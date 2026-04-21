package io.retrykit;

import java.util.Optional;

public record RetryContext(
        int attempt,
        int maxAttempts,
        Optional<Throwable> lastException,
        long elapsedMs
) {}
