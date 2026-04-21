package io.github.caninaam.retrykit;

import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PipelineDslParser {

    private static final Pattern STEP_PATTERN = Pattern.compile("^(\\w+)\\(([^)]*)\\)$");

    public static List<PipelineStep> parse(String dsl) {
        List<PipelineStep> steps = new ArrayList<>();
        for (String token : dsl.split(" > ")) {
            steps.add(parseStep(token.trim()));
        }
        return steps;
    }

    private static PipelineStep parseStep(String token) {
        Matcher m = STEP_PATTERN.matcher(token);
        if (!m.matches()) throw new IllegalArgumentException("Invalid DSL step: " + token);

        String name = m.group(1);
        String argsRaw = m.group(2).trim();
        Map<String, String> named = parseNamedArgs(argsRaw);

        return switch (name) {
            case "TIMEOUT"         -> parseTimeout(named, argsRaw);
            case "RETRY"           -> parseRetry(named, argsRaw);
            case "CB", "CIRCUIT_BREAKER" -> parseCb(named, argsRaw);
            case "FALLBACK"        -> parseFallback(named, argsRaw);
            default -> throw new IllegalArgumentException("Unknown pipeline step: " + name);
        };
    }

    private static PipelineStep.TimeoutStep parseTimeout(Map<String, String> named, String raw) {
        String val = named.getOrDefault("duration", positional(raw, 0));
        return new PipelineStep.TimeoutStep(parseDuration(val));
    }

    private static PipelineStep.RetryStep parseRetry(Map<String, String> named, String raw) {
        int maxAttempts = Integer.parseInt(named.getOrDefault("maxAttempts", positional(raw, 0)));
        double jitter = Double.parseDouble(named.getOrDefault("jitter", "0.0"));
        String backoffType = named.getOrDefault("backoff", "FIXED");

        BackoffStrategy backoff;
        if ("EXPONENTIAL".equals(backoffType)) {
            Duration initial = parseDuration(named.getOrDefault("initialDelay", "1s"));
            double mult = Double.parseDouble(named.getOrDefault("multiplier", "2.0"));
            Duration max = parseDuration(named.getOrDefault("maxDelay", "60s"));
            backoff = new BackoffStrategy.Exponential(initial, mult, max);
        } else {
            Duration delay = parseDuration(named.getOrDefault("waitDuration", "1s"));
            backoff = new BackoffStrategy.Fixed(delay);
        }

        return new PipelineStep.RetryStep(maxAttempts, backoff, jitter, List.of(Exception.class));
    }

    private static PipelineStep.CbStep parseCb(Map<String, String> named, String raw) {
        String rateRaw = named.getOrDefault("failureRate", positional(raw, 0));
        int rate = Integer.parseInt(rateRaw.endsWith("%") ? rateRaw.substring(0, rateRaw.length() - 1) : rateRaw);
        Duration wait = parseDuration(named.getOrDefault("wait", "10s"));
        Optional<Duration> timeout = named.containsKey("timeout")
                ? Optional.of(parseDuration(named.get("timeout")))
                : Optional.empty();
        return new PipelineStep.CbStep(rate, wait, timeout);
    }

    private static PipelineStep.FallbackStep parseFallback(Map<String, String> named, String raw) {
        if (named.containsKey("method")) {
            return new PipelineStep.FallbackStep(
                    PipelineStep.FallbackType.METHOD, Optional.empty(), Optional.of(named.get("method")));
        }
        if (named.containsKey("value")) {
            return new PipelineStep.FallbackStep(
                    PipelineStep.FallbackType.DEFAULT_VALUE, Optional.of(named.get("value")), Optional.empty());
        }
        return new PipelineStep.FallbackStep(PipelineStep.FallbackType.THROW, Optional.empty(), Optional.empty());
    }

    private static Map<String, String> parseNamedArgs(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw.isBlank()) return result;
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (p.contains(":")) {
                String[] kv = p.split(":", 2);
                result.put(kv[0].trim(), kv[1].trim());
            }
        }
        return result;
    }

    private static String positional(String raw, int index) {
        if (raw.isBlank()) return "";
        String[] parts = raw.split(",");
        if (index >= parts.length) return "";
        String part = parts[index].trim();
        return part.contains(":") ? "" : part;
    }

    public static Duration parseDuration(String s) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException("Empty duration");
        if (s.endsWith("ms")) return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2)));
        if (s.contains("m") && s.endsWith("s")) {
            String[] parts = s.split("m");
            return Duration.ofMinutes(Long.parseLong(parts[0]))
                    .plusSeconds(Long.parseLong(parts[1].substring(0, parts[1].length() - 1)));
        }
        if (s.endsWith("m")) return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1)));
        if (s.endsWith("s")) return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
        throw new IllegalArgumentException("Cannot parse duration: " + s);
    }
}
