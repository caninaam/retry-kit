package io.github.caninaam.retrykit;

import java.time.Duration;
import java.util.Optional;

/**
 * Configuration for the circuit breaker.
 *
 * <p>The circuit breaker has three states:
 * <ul>
 *   <li><b>CLOSED</b> — calls pass through normally, failures are counted</li>
 *   <li><b>OPEN</b>   — calls are blocked immediately (no service call made)</li>
 *   <li><b>HALF_OPEN</b> — a limited number of probe calls are allowed to test recovery</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * RetryKit.<String>retry()
 *     .circuitBreaker(cb -> cb
 *         .failureRateThreshold(50)
 *         .minimumNumberOfCalls(5)
 *         .waitDurationInOpenState(Duration.ofMinutes(1))
 *         .permittedCallsInHalfOpen(2))
 *     .build();
 * }</pre>
 *
 * <h2>Default values</h2>
 * <table border="1">
 *   <tr><th>Parameter</th><th>Default</th></tr>
 *   <tr><td>failureRateThreshold</td><td>50%</td></tr>
 *   <tr><td>minimumNumberOfCalls</td><td>5</td></tr>
 *   <tr><td>waitDurationInOpenState</td><td>10s</td></tr>
 *   <tr><td>slidingWindowSize</td><td>10</td></tr>
 *   <tr><td>permittedCallsInHalfOpen</td><td>3</td></tr>
 *   <tr><td>timeout</td><td>none</td></tr>
 * </table>
 */
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

    /**
     * Fluent builder for {@link CircuitBreakerConfig}.
     */
    public static final class Builder {
        private int failureRateThreshold = 50;
        private int minimumNumberOfCalls = 5;
        private Duration waitDurationInOpenState = Duration.ofSeconds(10);
        private int slidingWindowSize = 10;
        private int permittedCallsInHalfOpen = 3;
        private Duration timeout = null;

        /**
         * Percentage of failed calls required to open the circuit breaker.
         *
         * <p>Evaluated only after {@link #minimumNumberOfCalls} have been recorded.
         *
         * @param percent failure rate threshold (1–100), default: {@code 50}
         */
        public Builder failureRateThreshold(int percent) {
            this.failureRateThreshold = percent;
            return this;
        }

        /**
         * Minimum number of calls required before the failure rate is evaluated.
         *
         * <p>Prevents the CB from opening after just 1 failed call out of 1.
         *
         * @param n minimum number of calls, default: {@code 5}
         */
        public Builder minimumNumberOfCalls(int n) {
            this.minimumNumberOfCalls = n;
            return this;
        }

        /**
         * How long the circuit breaker stays OPEN before transitioning to HALF_OPEN.
         *
         * <p>During this period all calls are blocked immediately.
         *
         * @param d wait duration, default: {@code 10s}
         */
        public Builder waitDurationInOpenState(Duration d) {
            this.waitDurationInOpenState = d;
            return this;
        }

        /**
         * Size of the sliding window used to calculate the failure rate.
         *
         * <p>Only the last {@code n} calls are considered.
         *
         * @param n sliding window size, default: {@code 10}
         */
        public Builder slidingWindowSize(int n) {
            this.slidingWindowSize = n;
            return this;
        }

        /**
         * Number of probe calls allowed in HALF_OPEN state to test if the service recovered.
         *
         * <p>If all probes succeed → CB transitions to CLOSED.
         * If any probe fails → CB transitions back to OPEN.
         *
         * @param n number of permitted calls in HALF_OPEN, default: {@code 3}
         */
        public Builder permittedCallsInHalfOpen(int n) {
            this.permittedCallsInHalfOpen = n;
            return this;
        }

        /**
         * Maximum duration allowed for each individual call through the circuit breaker.
         *
         * <p>If the call exceeds this duration it is cancelled and counted as a failure.
         * Useful in pipeline mode to prevent slow services from blocking threads.
         *
         * <pre>{@code
         * .pipeline("RETRY(3) > CB(failureRate:50%, timeout:2s)")
         * }</pre>
         *
         * @param d per-call timeout, default: none (no per-call timeout)
         */
        public Builder timeout(Duration d) {
            this.timeout = d;
            return this;
        }

        /**
         * Builds the {@link CircuitBreakerConfig}.
         *
         * @return a new immutable {@link CircuitBreakerConfig}
         */
        public CircuitBreakerConfig build() {
            return new CircuitBreakerConfig(
                    failureRateThreshold, minimumNumberOfCalls,
                    waitDurationInOpenState, slidingWindowSize,
                    permittedCallsInHalfOpen, Optional.ofNullable(timeout)
            );
        }
    }
}
