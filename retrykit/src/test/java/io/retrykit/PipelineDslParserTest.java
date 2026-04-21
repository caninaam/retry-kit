package io.retrykit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PipelineDslParserTest {

    @Test
    void parseSingleRetry() {
        List<PipelineStep> steps = PipelineDslParser.parse("RETRY(3)");
        assertEquals(1, steps.size());
        assertInstanceOf(PipelineStep.RetryStep.class, steps.get(0));
        assertEquals(3, ((PipelineStep.RetryStep) steps.get(0)).maxAttempts());
    }

    @Test
    void parseSingleTimeout() {
        List<PipelineStep> steps = PipelineDslParser.parse("TIMEOUT(2s)");
        assertEquals(1, steps.size());
        PipelineStep.TimeoutStep ts = (PipelineStep.TimeoutStep) steps.get(0);
        assertEquals(Duration.ofSeconds(2), ts.duration());
    }

    @Test
    void parseSingleCb() {
        List<PipelineStep> steps = PipelineDslParser.parse("CB(50%)");
        assertEquals(1, steps.size());
        PipelineStep.CbStep cs = (PipelineStep.CbStep) steps.get(0);
        assertEquals(50, cs.failureRateThreshold());
    }

    @Test
    void parseChainThreeSteps() {
        List<PipelineStep> steps = PipelineDslParser.parse("TIMEOUT(2s) > RETRY(3) > CB(50%)");
        assertEquals(3, steps.size());
        assertInstanceOf(PipelineStep.TimeoutStep.class, steps.get(0));
        assertInstanceOf(PipelineStep.RetryStep.class, steps.get(1));
        assertInstanceOf(PipelineStep.CbStep.class, steps.get(2));
    }

    @Test
    void parseCbWithNamedWait() {
        List<PipelineStep> steps = PipelineDslParser.parse("CB(50%, wait:30s)");
        PipelineStep.CbStep cs = (PipelineStep.CbStep) steps.get(0);
        assertEquals(50, cs.failureRateThreshold());
        assertEquals(Duration.ofSeconds(30), cs.waitDurationOpen());
    }

    @Test
    void parseFallbackThrow() {
        List<PipelineStep> steps = PipelineDslParser.parse("FALLBACK(throw)");
        PipelineStep.FallbackStep fs = (PipelineStep.FallbackStep) steps.get(0);
        assertEquals(PipelineStep.FallbackType.THROW, fs.type());
    }

    @Test
    void parseFallbackValue() {
        List<PipelineStep> steps = PipelineDslParser.parse("FALLBACK(value:0)");
        PipelineStep.FallbackStep fs = (PipelineStep.FallbackStep) steps.get(0);
        assertEquals(PipelineStep.FallbackType.DEFAULT_VALUE, fs.type());
        assertEquals("0", fs.value().orElseThrow());
    }

    @Test
    void parseFallbackMethod() {
        List<PipelineStep> steps = PipelineDslParser.parse("FALLBACK(method:cached)");
        PipelineStep.FallbackStep fs = (PipelineStep.FallbackStep) steps.get(0);
        assertEquals(PipelineStep.FallbackType.METHOD, fs.type());
        assertEquals("cached", fs.method().orElseThrow());
    }

    @Test
    void parseRetryExponential() {
        List<PipelineStep> steps = PipelineDslParser.parse("RETRY(3, backoff:EXPONENTIAL)");
        PipelineStep.RetryStep rs = (PipelineStep.RetryStep) steps.get(0);
        assertEquals(3, rs.maxAttempts());
        assertInstanceOf(BackoffStrategy.Exponential.class, rs.backoff());
    }

    @Test
    void parseDurationMs() {
        assertEquals(Duration.ofMillis(500), PipelineDslParser.parseDuration("500ms"));
    }

    @Test
    void parseDurationMinutes() {
        assertEquals(Duration.ofMinutes(1), PipelineDslParser.parseDuration("1m"));
    }

    @Test
    void parseDurationMinutesAndSeconds() {
        assertEquals(Duration.ofSeconds(90), PipelineDslParser.parseDuration("1m30s"));
    }

    @Test
    void invalidStepThrows() {
        assertThrows(IllegalArgumentException.class, () -> PipelineDslParser.parse("UNKNOWN(3)"));
    }
}
