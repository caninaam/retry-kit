package io.retrykit;

import java.time.Duration;
import java.util.Optional;

public record CircuitBreakerConfig(
        int failureRateThreshold,
        int minimumNumberOfCalls,
        Duration waitDurationInOpenState,
        int slidingWindowSize,
        int permittedCallsInHalfOpen,
        Optional<Duration> timeout
) {
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int failureRateThreshold = 50;
        private int minimumNumberOfCalls = 5;
        private Duration waitDurationInOpenState = Duration.ofSeconds(10);
        private int slidingWindowSize = 10;
        private int permittedCallsInHalfOpen = 3;
        private Duration timeout = null;

        public Builder failureRateThreshold(int percent) {
            this.failureRateThreshold = percent;
            return this;
        }

        public Builder minimumNumberOfCalls(int n) {
            this.minimumNumberOfCalls = n;
            return this;
        }

        public Builder waitDurationInOpenState(Duration d) {
            this.waitDurationInOpenState = d;
            return this;
        }

        public Builder slidingWindowSize(int n) {
            this.slidingWindowSize = n;
            return this;
        }

        public Builder permittedCallsInHalfOpen(int n) {
            this.permittedCallsInHalfOpen = n;
            return this;
        }

        public Builder timeout(Duration d) {
            this.timeout = d;
            return this;
        }

        public CircuitBreakerConfig build() {
            return new CircuitBreakerConfig(
                    failureRateThreshold, minimumNumberOfCalls,
                    waitDurationInOpenState, slidingWindowSize,
                    permittedCallsInHalfOpen, Optional.ofNullable(timeout)
            );
        }
    }
}
