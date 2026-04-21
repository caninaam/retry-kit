package io.github.caninaam.retrykit;

import java.time.Duration;

public sealed interface BackoffStrategy permits BackoffStrategy.Fixed, BackoffStrategy.Exponential {

    Duration nextDelay(int attempt);

    record Fixed(Duration delay) implements BackoffStrategy {
        public Duration nextDelay(int attempt) {
            return delay;
        }
    }

    record Exponential(Duration initial, double multiplier, Duration maxDelay) implements BackoffStrategy {
        public Duration nextDelay(int attempt) {
            double ms = initial.toMillis() * Math.pow(multiplier, attempt - 1);
            return Duration.ofMillis(Math.min((long) ms, maxDelay.toMillis()));
        }
    }
}
