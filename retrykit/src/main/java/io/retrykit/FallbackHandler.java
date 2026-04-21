package io.retrykit;

@FunctionalInterface
public interface FallbackHandler<T> {
    T apply(RetryContext context) throws Exception;
}
