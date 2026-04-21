package io.github.caninaam.retrykit;

public class CircuitBreakerOpenException extends RuntimeException {

    private final CircuitBreaker.State state;

    public CircuitBreakerOpenException(CircuitBreaker.State state) {
        super("Circuit breaker is " + state + " — call not permitted");
        this.state = state;
    }

    public CircuitBreaker.State cbState() {
        return state;
    }
}
