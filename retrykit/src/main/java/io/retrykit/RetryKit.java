package io.retrykit;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class RetryKit<T> {

    private volatile WorkflowEngine<T> engine;
    private final FallbackHandler<T> fallback;
    private HotReloadWatcher watcher;

    private RetryKit(WorkflowEngine<T> engine, FallbackHandler<T> fallback) {
        this.engine = engine;
        this.fallback = fallback;
    }

    public T call(Callable<T> callable) throws Exception {
        return engine.execute(callable, fallback);
    }

    public T call(Callable<T> callable, String label) throws Exception {
        String previous = RetryKitContext.get();
        RetryKitContext.set(label);
        try { return engine.execute(callable, fallback); }
        finally { RetryKitContext.set(previous); }
    }

    public CompletableFuture<T> callAsync(Callable<T> callable) {
        return CompletableFuture.supplyAsync(() -> {
            try { return call(callable); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    public void stop() {
        if (watcher != null) watcher.stop();
    }

    /** Returns the circuit breaker state, or empty if no CB is configured. */
    public Optional<CircuitBreaker.State> circuitBreakerState() {
        return engine.circuitBreakerState();
    }

    // Package-private: used by ProfileBuilder for hot reload
    void updateEngine(WorkflowEngine<T> newEngine) {
        this.engine = newEngine;
    }

    // ── Entry points ─────────────────────────────────────────────────────────

    public static <T> Builder<T> retry() {
        return new Builder<>();
    }

    public static YamlBuilder fromYaml(String yamlPath) {
        return new YamlBuilder(yamlPath);
    }

    // ── Fluent builder ───────────────────────────────────────────────────────

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

        public Builder<T> name(String name) { this.name = name; return this; }
        public Builder<T> mode(WorkflowMode mode) { this.mode = mode; return this; }

        public Builder<T> maxAttempts(int n) { retryConfigBuilder.maxAttempts(n); return this; }
        public Builder<T> waitDuration(Duration d) { retryConfigBuilder.waitDuration(d); return this; }
        public Builder<T> exponentialBackoff(Duration initial, double mult) { retryConfigBuilder.exponentialBackoff(initial, mult); return this; }
        public Builder<T> exponentialBackoff(Duration initial, double mult, Duration max) { retryConfigBuilder.exponentialBackoff(initial, mult, max); return this; }
        public Builder<T> withJitter(double factor) { retryConfigBuilder.withJitter(factor); return this; }
        public Builder<T> maxDuration(Duration d) { retryConfigBuilder.maxDuration(d); return this; }

        @SafeVarargs
        public final Builder<T> retryOn(Class<? extends Throwable>... ex) { retryConfigBuilder.retryOn(ex); return this; }
        public Builder<T> retryIf(Predicate<T> pred) { retryConfigBuilder.retryIf(pred); return this; }

        public Builder<T> onRetry(Consumer<RetryContext> cb) { this.onRetry = cb; return this; }
        public Builder<T> onSuccess(Consumer<T> cb) { this.onSuccess = cb; return this; }
        public Builder<T> onFailure(Consumer<Throwable> cb) { this.onFailure = cb; return this; }
        public Builder<T> fallback(FallbackHandler<T> handler) { this.fallback = handler; return this; }

        public Builder<T> circuitBreaker(Consumer<CircuitBreakerConfig.Builder> cfg) {
            this.cbConfigBuilder = CircuitBreakerConfig.builder();
            cfg.accept(this.cbConfigBuilder);
            return this;
        }

        public Builder<T> pipeline(String dsl) {
            this.pipelineSteps = PipelineDslParser.parse(dsl);
            this.mode = WorkflowMode.PIPELINE;
            return this;
        }

        public RetryKit<T> build() {
            WorkflowEngine<T> engine;
            if (mode == WorkflowMode.PIPELINE && pipelineSteps != null) {
                engine = new WorkflowEngine<>(new PipelineExecutor<>(pipelineSteps));
            } else {
                RetryExecutor<T> executor = new RetryExecutor<>(retryConfigBuilder.build())
                        .name(name).onRetry(onRetry).onSuccess(onSuccess).onFailure(onFailure);
                // With CB, fallback is handled by WorkflowEngine so CB can record the failure correctly
                if (fallback != null && cbConfigBuilder == null) executor.fallback(fallback);
                Optional<CircuitBreaker> cb = Optional.ofNullable(cbConfigBuilder)
                        .map(b -> new CircuitBreaker(b.build()));
                engine = new WorkflowEngine<>(mode, executor, cb);
            }
            return new RetryKit<>(engine, fallback);
        }

        public T call(Callable<T> callable) throws Exception { return build().call(callable); }
        public T call(Callable<T> callable, String label) throws Exception { return build().call(callable, label); }
        public CompletableFuture<T> callAsync(Callable<T> callable) { return build().callAsync(callable); }
    }

    // ── YAML builder ─────────────────────────────────────────────────────────

    public static final class YamlBuilder {
        private final String yamlPath;
        private String profileName = "default";

        YamlBuilder(String yamlPath) { this.yamlPath = yamlPath; }

        public YamlBuilder profile(String name) { this.profileName = name; return this; }

        public <T> ProfileBuilder<T> as() {
            return new ProfileBuilder<>(yamlPath, profileName);
        }
    }

    public static final class ProfileBuilder<T> {

        private final String yamlPath;
        private final String profileName;
        private FallbackHandler<T> fallback = null;
        private Duration hotReloadInterval = null;

        ProfileBuilder(String yamlPath, String profileName) {
            this.yamlPath = yamlPath;
            this.profileName = profileName;
        }

        public ProfileBuilder<T> fallback(FallbackHandler<T> handler) { this.fallback = handler; return this; }

        public ProfileBuilder<T> withHotReload(Duration interval) {
            this.hotReloadInterval = interval;
            return this;
        }

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
