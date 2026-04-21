package io.retrykit;

public class RetryException extends RuntimeException {

    private final int attempts;

    public RetryException(int attempts, Throwable cause) {
        super("All " + attempts + " attempt(s) failed", cause);
        this.attempts = attempts;
    }

    public int attempts() {
        return attempts;
    }
}
