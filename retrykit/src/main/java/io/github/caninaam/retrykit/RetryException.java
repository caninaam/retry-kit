package io.github.caninaam.retrykit;

/**
 * Thrown when all retry attempts have been exhausted and no fallback is configured.
 *
 * <p>This exception means the service <b>was called</b> but all attempts failed.
 *
 * <pre>{@code
 * try {
 *     kit.call(() -> myService.call());
 * } catch (RetryException e) {
 *     System.out.println("Failed after " + e.attempts() + " attempts");
 *     System.out.println("Last error: " + e.getCause().getMessage());
 * }
 * }</pre>
 *
 * @see CircuitBreakerOpenException
 */
public class RetryException extends RuntimeException {

    private final int attempts;

    public RetryException(int attempts, Throwable cause) {
        super("All " + attempts + " attempt(s) failed", cause);
        this.attempts = attempts;
    }

    /**
     * Returns the total number of attempts made before giving up.
     *
     * @return number of attempts (always &gt;= 1)
     */
    public int attempts() {
        return attempts;
    }
}
