package io.github.caninaam.retrykit;

/**
 * Thrown when a call is rejected because the circuit breaker is OPEN.
 *
 * <p>This exception means the service was <b>not called</b> — the circuit breaker
 * blocked the call immediately based on previous failures.
 *
 * <pre>{@code
 * try {
 *     kit.call(() -> myService.call());
 * } catch (CircuitBreakerOpenException e) {
 *     System.out.println("Service known to be down, not called");
 *     System.out.println("CB state: " + e.cbState()); // OPEN or HALF_OPEN
 * } catch (RetryException e) {
 *     System.out.println("Service called but failed after " + e.attempts() + " attempts");
 * }
 * }</pre>
 *
 * @see RetryException
 */
public class CircuitBreakerOpenException extends RuntimeException {

    private final CircuitBreaker.State state;

    public CircuitBreakerOpenException(CircuitBreaker.State state) {
        super("Circuit breaker is " + state + " — call not permitted");
        this.state = state;
    }

    /**
     * Returns the circuit breaker state at the time the call was rejected.
     *
     * @return {@link CircuitBreaker.State#OPEN} or {@link CircuitBreaker.State#HALF_OPEN}
     */
    public CircuitBreaker.State cbState() {
        return state;
    }
}
