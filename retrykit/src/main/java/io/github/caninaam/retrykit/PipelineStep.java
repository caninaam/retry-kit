package io.github.caninaam.retrykit;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public sealed interface PipelineStep
        permits PipelineStep.RetryStep, PipelineStep.CbStep,
                PipelineStep.TimeoutStep, PipelineStep.FallbackStep {

    record RetryStep(
            int maxAttempts,
            BackoffStrategy backoff,
            double jitter,
            List<Class<? extends Throwable>> retryOn
    ) implements PipelineStep {}

    record CbStep(
            int failureRateThreshold,
            Duration waitDurationOpen,
            Optional<Duration> timeout
    ) implements PipelineStep {}

    record TimeoutStep(Duration duration) implements PipelineStep {}

    enum FallbackType { THROW, DEFAULT_VALUE, METHOD }

    record FallbackStep(
            FallbackType type,
            Optional<String> value,
            Optional<String> method
    ) implements PipelineStep {}
}
