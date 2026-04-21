package io.retrykit;

import java.util.List;
import java.util.Optional;

public record WorkflowConfig(
        String name,
        WorkflowMode mode,
        Optional<RetryConfig<?>> retryConfig,
        Optional<CircuitBreakerConfig> cbConfig,
        List<PipelineStep> pipelineSteps
) {}
