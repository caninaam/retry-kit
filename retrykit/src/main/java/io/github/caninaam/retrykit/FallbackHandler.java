package io.github.caninaam.retrykit;

@FunctionalInterface
public interface FallbackHandler<T> {
    T apply(RetryContext context) throws Exception;
}
