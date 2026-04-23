package io.github.caninaam.retrykit;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Entry point for the RetryKit library.
 *
 * <p>RetryKit provides retry, circuit breaker, and pipeline composition
 * with zero runtime dependencies.
 *
 * <h2>Quick start</h2>
 * <pre>{@code
 * String result = RetryKit.<String>retry()
 *     .maxAttempts(3)
 *     .waitDuration(Duration.ofSeconds(1))
 *     .fallback(ctx -> "default")
 *     .call(() -> myService.call());
 * }</pre>
 *
 * <h2>With circuit breaker</h2>
 * <pre>{@code
 * RetryKit<String> kit = RetryKit.<String>retry()
 *     .mode(WorkflowMode.CB_FIRST)
 *     .maxAttempts(3)
 *     .circuitBreaker(cb -> cb
 *         .failureRateThreshold(50)
 *         .minimumNumberOfCalls(5)
 *         .waitDurationInOpenState(Duration.ofMinutes(1)))
 *     .fallback(ctx -> "fallback")
 *     .build();
 * }</pre>
 *
 * <h2>With pipeline DSL</h2>
 * <pre>{@code
 * RetryKit.<String>retry()
 *     .pipeline("TIMEOUT(3s) > RETRY(3) > CB(50%)")
 *     .call(() -> myService.call());
 * }</pre>
 *
 * <h2>From YAML config</h2>
 * <pre>{@code
 * RetryKit<String> kit = RetryKit.<String>fromYaml("retrykit.yaml")
 *     .profile("default")
 *     .<String>as()
 *     .withHotReload(Duration.ofSeconds(5))
 *     .build();
 * }</pre>
 *
 * @param <T> the return type of the callable
 */
public final class RetryKit<T> {

    private volatile WorkflowEngine<T> engine;
    private final FallbackHandler<T> fallback;
    private HotReloadWatcher watcher;

    private RetryKit(WorkflowEngine<T> engine, FallbackHandler<T> fallback) {
        this.engine = engine;
        this.fallback = fallback;
    }

    /**
     * Executes the callable with the configured retry/CB policy.
     *
     * @param callable the operation to execute
     * @return the result of the callable, or the fallback value if all attempts fail
     * @throws RetryException if all attempts fail and no fallback is configured
     * @throws CircuitBreakerOpenException if the circuit breaker is OPEN and no fallback is configured
     * @throws Exception if a non-retryable exception is thrown
     */
    public T call(Callable<T> callable) throws Exception {
        return engine.execute(callable, fallback);
    }

    /**
     * Executes the callable with a label used in log messages.
     *
     * <pre>{@code
     * kit.call(() -> service.fetchOrders(), "GET /orders");
     * // logs: [RetryKit] [GET /orders] Attempt 1/3 FAILED
     * }</pre>
     *
     * @param callable the operation to execute
     * @param label    label shown in log messages (e.g. "GET /orders")
     * @return the result of the callable, or the fallback value if all attempts fail
     * @throws Exception if a non-retryable exception is thrown or all attempts fail
     */
    public T call(Callable<T> callable, String label) throws Exception {
        String previous = RetryKitContext.get();
        RetryKitContext.set(label);
        try { return engine.execute(callable, fallback); }
        finally { RetryKitContext.set(previous); }
    }

    /**
     * Executes the callable asynchronously using {@link CompletableFuture}.
     *
     * @param callable the operation to execute
     * @return a CompletableFuture that completes with the result or exceptionally on failure
     */
    public CompletableFuture<T> callAsync(Callable<T> callable) {
        return CompletableFuture.supplyAsync(() -> {
            try { return call(callable); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    /**
     * Stops the hot-reload watcher if one is configured.
     * Call this when the application shuts down.
     */
    public void stop() {
        if (watcher != null) watcher.stop();
    }

    /**
     * Returns the current circuit breaker state.
     *
     * @return {@code Optional.empty()} if no circuit breaker is configured,
     *         otherwise the current {@link CircuitBreaker.State}
     */
    public Optional<CircuitBreaker.State> circuitBreakerState() {
        return engine.circuitBreakerState();
    }

    void updateEngine(WorkflowEngine<T> newEngine) {
        this.engine = newEngine;
    }

    // ── Entry points ─────────────────────────────────────────────────────────

    /**
     * Creates a new fluent builder.
     *
     * @param <T> the return type of the callable
     * @return a new {@link Builder}
     */
    public static <T> Builder<T> retry() {
        return new Builder<>();
    }

    /**
     * Creates a builder that loads configuration from a YAML file.
     *
     * <p>The YAML file must be <b>outside the JAR</b> for hot reload to work.
     *
     * @param yamlPath path to the YAML config file (absolute or relative to working directory)
     * @return a new {@link YamlBuilder}
     */
    public static YamlBuilder fromYaml(String yamlPath) {
        return new YamlBuilder(yamlPath);
    }

    // ── Fluent builder ───────────────────────────────────────────────────────

    /**
     * Fluent builder for configuring a {@link RetryKit} instance.
     *
     * @param <T> the return type of the callable
     */
    public static final class Builder<T> {

        private WorkflowMode mode = WorkflowMode.RETRY_FIRST;
        private final RetryConfig.Builder<T> retryConfigBuilder = RetryConfig.builder();
        private CircuitBreakerConfig.Builder cbConfigBuilder = null;
        private FallbackHandler<T> fallback = null;
        private Consumer<RetryContext> onRetry = ctx -> {};
        private Consumer<T> onSuccess = t -> {};
        private Consumer<Throwable> onFailure = t -> {};
        private List<PipelineStep> pipelineSteps = null;
        private String name = "";

        /**
         * Sets a name for this kit, used in log messages.
         *
         * @param name a descriptive name (e.g. "orderService")
         */
        public Builder<T> name(String name) { this.name = name; return this; }

        /**
         * Sets the workflow mode when combining retry and circuit breaker.
         *
         * @param mode {@link WorkflowMode#RETRY_FIRST} retries first then checks CB,
         *             {@link WorkflowMode#CB_FIRST} checks CB first then retries
         */
        public Builder<T> mode(WorkflowMode mode) { this.mode = mode; return this; }

        /**
         * Sets the maximum number of attempts (including the first call).
         *
         * @param n total number of attempts (e.g. 3 = 1 initial + 2 retries)
         */
        public Builder<T> maxAttempts(int n) { retryConfigBuilder.maxAttempts(n); return this; }

        /**
         * Sets a fixed delay between retry attempts.
         *
         * @param d duration to wait between attempts (e.g. {@code Duration.ofSeconds(1)})
         */
        public Builder<T> waitDuration(Duration d) { retryConfigBuilder.waitDuration(d); return this; }

        /**
         * Enables exponential backoff with a cap.
         *
         * @param initial    initial delay for the first retry
         * @param multiplier delay multiplier applied after each attempt (e.g. 2.0 doubles the delay)
         * @param max        maximum delay — delays are capped at this value
         */
        public Builder<T> exponentialBackoff(Duration initial, double multiplier, Duration max) {
            retryConfigBuilder.exponentialBackoff(initial, multiplier, max); return this;
        }

        /**
         * Enables exponential backoff without a cap.
         *
         * @param initial    initial delay for the first retry
         * @param multiplier delay multiplier applied after each attempt (e.g. 2.0 doubles the delay)
         */
        public Builder<T> exponentialBackoff(Duration initial, double multiplier) {
            retryConfigBuilder.exponentialBackoff(initial, multiplier); return this;
        }

        /**
         * Adds random jitter to retry delays to avoid thundering herd.
         *
         * @param factor jitter factor between 0.0 and 1.0
         *               (e.g. 0.2 adds up to ±20% random noise to each delay)
         */
        public Builder<T> withJitter(double factor) { retryConfigBuilder.withJitter(factor); return this; }

        /**
         * Sets a maximum total duration for all retry attempts combined.
         * Retrying stops when this duration is exceeded, regardless of {@code maxAttempts}.
         *
         * @param d maximum total duration (e.g. {@code Duration.ofSeconds(5)})
         */
        public Builder<T> maxDuration(Duration d) { retryConfigBuilder.maxDuration(d); return this; }

        /**
         * Restricts retrying to specific exception types.
         * All other exceptions are propagated immediately without retrying.
         *
         * @param ex exception classes that should trigger a retry
         */
        @SafeVarargs
        public final Builder<T> retryOn(Class<? extends Throwable>... ex) {
            retryConfigBuilder.retryOn(ex); return this;
        }

        /**
         * Retries when the result matches a predicate (result-based retry).
         *
         * <pre>{@code
         * .retryIf(result -> "retry-me".equals(result))
         * }</pre>
         *
         * @param pred predicate — returns {@code true} if the result should be retried
         */
        public Builder<T> retryIf(Predicate<T> pred) { retryConfigBuilder.retryIf(pred); return this; }

        /**
         * Registers a callback invoked after each failed attempt.
         *
         * @param cb consumer receiving the {@link RetryContext} with attempt details
         */
        public Builder<T> onRetry(Consumer<RetryContext> cb) { this.onRetry = cb; return this; }

        /**
         * Registers a callback invoked on successful completion.
         *
         * @param cb consumer receiving the successful result
         */
        public Builder<T> onSuccess(Consumer<T> cb) { this.onSuccess = cb; return this; }

        /**
         * Registers a callback invoked when all attempts fail.
         *
         * @param cb consumer receiving the last exception
         */
        public Builder<T> onFailure(Consumer<Throwable> cb) { this.onFailure = cb; return this; }

        /**
         * Sets a fallback called when all attempts fail or the circuit breaker is OPEN.
         *
         * <pre>{@code
         * .fallback(ctx -> "default value")
         * }</pre>
         *
         * @param handler fallback receiving a {@link RetryContext} with max attempts info
         */
        public Builder<T> fallback(FallbackHandler<T> handler) { this.fallback = handler; return this; }

        /**
         * Adds a circuit breaker with the given configuration.
         *
         * <pre>{@code
         * .circuitBreaker(cb -> cb
         *     .failureRateThreshold(50)
         *     .minimumNumberOfCalls(5)
         *     .waitDurationInOpenState(Duration.ofMinutes(1))
         *     .permittedCallsInHalfOpen(2))
         * }</pre>
         *
         * @param cfg consumer that configures the {@link CircuitBreakerConfig.Builder}
         */
        public Builder<T> circuitBreaker(Consumer<CircuitBreakerConfig.Builder> cfg) {
            this.cbConfigBuilder = CircuitBreakerConfig.builder();
            cfg.accept(this.cbConfigBuilder);
            return this;
        }

        /**
         * Switches to pipeline mode using the given DSL string.
         *
         * <pre>{@code
         * .pipeline("TIMEOUT(3s) > RETRY(maxAttempts:3, waitDuration:1s) > CB(failureRate:50%)")
         * }</pre>
         *
         * <p>DSL syntax:
         * <ul>
         *   <li>{@code TIMEOUT(5s)} — global timeout</li>
         *   <li>{@code RETRY(3)} or {@code RETRY(maxAttempts:3, waitDuration:500ms)}</li>
         *   <li>{@code CB(50%)} or {@code CB(failureRate:50%, minCalls:5, wait:1m, timeout:2s)}</li>
         * </ul>
         *
         * @param dsl pipeline DSL string — steps separated by {@code >}
         */
        public Builder<T> pipeline(String dsl) {
            this.pipelineSteps = PipelineDslParser.parse(dsl);
            this.mode = WorkflowMode.PIPELINE;
            return this;
        }

        /**
         * Builds and returns the configured {@link RetryKit} instance.
         *
         * @return a new {@link RetryKit} ready to use
         */
        public RetryKit<T> build() {
            WorkflowEngine<T> engine;
            if (mode == WorkflowMode.PIPELINE && pipelineSteps != null) {
                engine = new WorkflowEngine<>(new PipelineExecutor<>(pipelineSteps));
            } else {
                RetryExecutor<T> executor = new RetryExecutor<>(retryConfigBuilder.build())
                        .name(name).onRetry(onRetry).onSuccess(onSuccess).onFailure(onFailure);
                if (fallback != null && cbConfigBuilder == null) executor.fallback(fallback);
                Optional<CircuitBreaker> cb = Optional.ofNullable(cbConfigBuilder)
                        .map(b -> new CircuitBreaker(b.build()));
                engine = new WorkflowEngine<>(mode, executor, cb);
            }
            return new RetryKit<>(engine, fallback);
        }

        /** Shortcut — builds and immediately calls the callable. */
        public T call(Callable<T> callable) throws Exception { return build().call(callable); }

        /** Shortcut — builds and immediately calls the callable with a log label. */
        public T call(Callable<T> callable, String label) throws Exception { return build().call(callable, label); }

        /** Shortcut — builds and immediately calls the callable asynchronously. */
        public CompletableFuture<T> callAsync(Callable<T> callable) { return build().callAsync(callable); }
    }

    // ── YAML builder ─────────────────────────────────────────────────────────

    /**
     * Builder that loads configuration from a YAML file.
     *
     * @see RetryKit#fromYaml(String)
     */
    public static final class YamlBuilder {
        private final String yamlPath;
        private String profileName = "default";

        YamlBuilder(String yamlPath) { this.yamlPath = yamlPath; }

        /**
         * Selects a named profile from the YAML file.
         *
         * @param name profile name (default: {@code "default"})
         */
        public YamlBuilder profile(String name) { this.profileName = name; return this; }

        /**
         * Specifies the return type and transitions to {@link ProfileBuilder}.
         *
         * @param <T> the return type of the callable
         */
        public <T> ProfileBuilder<T> as() {
            return new ProfileBuilder<>(yamlPath, profileName);
        }
    }

    /**
     * Builder for YAML-loaded profiles with optional hot reload.
     *
     * @param <T> the return type of the callable
     */
    public static final class ProfileBuilder<T> {

        private final String yamlPath;
        private final String profileName;
        private FallbackHandler<T> fallback = null;
        private Duration hotReloadInterval = null;

        ProfileBuilder(String yamlPath, String profileName) {
            this.yamlPath = yamlPath;
            this.profileName = profileName;
        }

        /**
         * Sets a fallback called when all attempts fail.
         *
         * @param handler fallback handler
         */
        public ProfileBuilder<T> fallback(FallbackHandler<T> handler) { this.fallback = handler; return this; }

        /**
         * Enables hot reload — the YAML file is re-read at the given interval.
         * Config updates take effect without restarting the application.
         *
         * <p>The YAML file must be <b>outside the JAR</b> (not in classpath).
         *
         * @param interval how often to check for config changes (e.g. {@code Duration.ofSeconds(5)})
         */
        public ProfileBuilder<T> withHotReload(Duration interval) {
            this.hotReloadInterval = interval;
            return this;
        }

        /**
         * Builds and returns the configured {@link RetryKit} instance.
         *
         * @return a new {@link RetryKit} ready to use
         * @throws IllegalArgumentException if the profile is not found in the YAML file
         * @throws Exception if the YAML file cannot be read
         */
        public RetryKit<T> build() throws Exception {
            YamlConfigLoader loader = new YamlConfigLoader(yamlPath);
            WorkflowConfig config = loader.load().get(profileName);
            if (config == null) throw new IllegalArgumentException("Profile not found: " + profileName);

            RetryKit<T> kit = new RetryKit<>(buildEngine(config), fallback);

            if (hotReloadInterval != null) {
                HotReloadWatcher watcher = new HotReloadWatcher(yamlPath, hotReloadInterval, () -> {
                    try {
                        WorkflowConfig newConfig = loader.load().get(profileName);
                        if (newConfig != null) kit.updateEngine(buildEngine(newConfig));
                    } catch (Exception ignored) {
                        // Keep running with previous config on reload failure
                    }
                });
                watcher.start();
                kit.watcher = watcher;
            }

            return kit;
        }

        /** Shortcut — builds and immediately calls the callable. */
        public T call(Callable<T> callable) throws Exception { return build().call(callable); }

        @SuppressWarnings("unchecked")
        private WorkflowEngine<T> buildEngine(WorkflowConfig config) {
            if (config.mode() == WorkflowMode.PIPELINE) {
                return new WorkflowEngine<>(new PipelineExecutor<>(config.pipelineSteps()));
            }
            RetryConfig<T> retryConfig = config.retryConfig()
                    .map(r -> (RetryConfig<T>) r)
                    .orElse(RetryConfig.<T>builder().build());
            RetryExecutor<T> executor = new RetryExecutor<>(retryConfig);
            if (fallback != null) executor.fallback(fallback);
            Optional<CircuitBreaker> cb = config.cbConfig().map(CircuitBreaker::new);
            return new WorkflowEngine<>(config.mode(), executor, cb);
        }
    }
}
