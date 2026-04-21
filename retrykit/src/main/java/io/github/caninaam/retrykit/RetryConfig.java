package io.github.caninaam.retrykit;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public record RetryConfig<T>(
        int maxAttempts,
        BackoffStrategy backoff,
        double jitter,
        List<Class<? extends Throwable>> retryOn,
        Optional<Predicate<T>> retryIf,
        Optional<Duration> maxDuration
) {
    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static final class Builder<T> {
        private int maxAttempts = 3;
        private BackoffStrategy backoff = new BackoffStrategy.Fixed(Duration.ofSeconds(1));
        private double jitter = 0.0;
        private List<Class<? extends Throwable>> retryOn = List.of(Exception.class);
        private Predicate<T> retryIf = null;
        private Duration maxDuration = null;

        public Builder<T> maxAttempts(int n) {
            this.maxAttempts = n;
            return this;
        }

        public Builder<T> waitDuration(Duration d) {
            this.backoff = new BackoffStrategy.Fixed(d);
            return this;
        }

        public Builder<T> exponentialBackoff(Duration initial, double multiplier, Duration maxDelay) {
            this.backoff = new BackoffStrategy.Exponential(initial, multiplier, maxDelay);
            return this;
        }

        public Builder<T> exponentialBackoff(Duration initial, double multiplier) {
            return exponentialBackoff(initial, multiplier, Duration.ofSeconds(60));
        }

        public Builder<T> withJitter(double factor) {
            this.jitter = factor;
            return this;
        }

        @SafeVarargs
        public final Builder<T> retryOn(Class<? extends Throwable>... exceptions) {
            this.retryOn = List.of(exceptions);
            return this;
        }

        public Builder<T> retryIf(Predicate<T> predicate) {
            this.retryIf = predicate;
            return this;
        }

        public Builder<T> maxDuration(Duration d) {
            this.maxDuration = d;
            return this;
        }

        public RetryConfig<T> build() {
            return new RetryConfig<>(maxAttempts, backoff, jitter, retryOn,
                    Optional.ofNullable(retryIf), Optional.ofNullable(maxDuration));
        }
    }
}
