package io.github.caninaam.retrykit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

public class YamlConfigLoader {

    private final Path path;

    public YamlConfigLoader(String yamlPath) {
        this.path = Path.of(yamlPath);
    }

    public Map<String, WorkflowConfig> load() throws IOException {
        List<String> lines = Files.readAllLines(path);
        int[] pos = {0};
        Map<String, Object> root = parseBlock(lines, pos, 0);

        @SuppressWarnings("unchecked")
        Map<String, Object> retrykit = (Map<String, Object>) root.get("retrykit");
        if (retrykit == null) return Map.of();

        // Apply logging config if present
        applyLoggingConfig(retrykit);

        @SuppressWarnings("unchecked")
        Map<String, Object> profiles = (Map<String, Object>) retrykit.get("profiles");
        if (profiles == null) return Map.of();

        Map<String, WorkflowConfig> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : profiles.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> profileMap = (Map<String, Object>) entry.getValue();
            result.put(entry.getKey(), buildProfile(entry.getKey(), profileMap));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private WorkflowConfig buildProfile(String name, Map<String, Object> map) {
        WorkflowMode mode = WorkflowMode.valueOf(map.getOrDefault("mode", "RETRY_FIRST").toString());

        Optional<RetryConfig<?>> retryConfig = Optional.empty();
        if (map.containsKey("retry")) {
            retryConfig = Optional.of(buildRetryConfig((Map<String, Object>) map.get("retry")));
        }

        Optional<CircuitBreakerConfig> cbConfig = Optional.empty();
        if (map.containsKey("circuitBreaker")) {
            cbConfig = Optional.of(buildCbConfig((Map<String, Object>) map.get("circuitBreaker")));
        }

        List<PipelineStep> steps = List.of();
        if (mode == WorkflowMode.PIPELINE && map.containsKey("pipeline")) {
            Object pipeline = map.get("pipeline");
            if (pipeline instanceof String dsl) {
                steps = PipelineDslParser.parse(dsl);
            } else if (pipeline instanceof List<?> list) {
                steps = buildStepsFromList((List<Map<String, Object>>) list);
            }
        }

        return new WorkflowConfig(name, mode, retryConfig, cbConfig, steps);
    }

    @SuppressWarnings("unchecked")
    private RetryConfig<?> buildRetryConfig(Map<String, Object> m) {
        RetryConfig.Builder<?> b = RetryConfig.builder();
        if (m.containsKey("maxAttempts")) b.maxAttempts(Integer.parseInt(m.get("maxAttempts").toString()));

        String backoff = m.getOrDefault("backoff", "FIXED").toString();
        if ("EXPONENTIAL".equals(backoff)) {
            Duration initial = parseDuration(m.getOrDefault("initialDelay", "1s").toString());
            double mult = Double.parseDouble(m.getOrDefault("multiplier", "2.0").toString());
            Duration max = parseDuration(m.getOrDefault("maxDelay", "60s").toString());
            b.exponentialBackoff(initial, mult, max);
        } else if (m.containsKey("initialDelay")) {
            b.waitDuration(parseDuration(m.get("initialDelay").toString()));
        }

        if (m.containsKey("jitter")) b.withJitter(Double.parseDouble(m.get("jitter").toString()));
        if (m.containsKey("maxDuration")) b.maxDuration(parseDuration(m.get("maxDuration").toString()));

        if (m.containsKey("retryOn")) {
            List<String> names = (List<String>) m.get("retryOn");
            b.retryOn(resolveExceptions(names));
        }

        return b.build();
    }

    private CircuitBreakerConfig buildCbConfig(Map<String, Object> m) {
        CircuitBreakerConfig.Builder b = CircuitBreakerConfig.builder();
        if (m.containsKey("failureRateThreshold"))
            b.failureRateThreshold(Integer.parseInt(m.get("failureRateThreshold").toString()));
        if (m.containsKey("waitDurationOpen"))
            b.waitDurationInOpenState(parseDuration(m.get("waitDurationOpen").toString()));
        if (m.containsKey("timeout"))
            b.timeout(parseDuration(m.get("timeout").toString()));
        return b.build();
    }

    @SuppressWarnings("unchecked")
    private void applyLoggingConfig(Map<String, Object> retrykit) {
        if (!retrykit.containsKey("logging")) return;
        Map<String, Object> logging = (Map<String, Object>) retrykit.get("logging");
        if (logging.containsKey("enabled")) {
            RetryKitLogger.setEnabled(Boolean.parseBoolean(logging.get("enabled").toString()));
        }
        if (logging.containsKey("level")) {
            String lvl = logging.get("level").toString().toUpperCase();
            RetryKitLogger.setLevel(lvl.equals("DEBUG")
                    ? RetryKitLogger.LogLevel.DEBUG
                    : RetryKitLogger.LogLevel.INFO);
        }
    }

    @SuppressWarnings("unchecked")
    private List<PipelineStep> buildStepsFromList(List<Map<String, Object>> list) {
        List<PipelineStep> steps = new ArrayList<>();
        for (Map<String, Object> item : list) {
            String stepName = item.getOrDefault("step", "").toString();
            steps.add(switch (stepName) {
                case "TIMEOUT" -> new PipelineStep.TimeoutStep(
                        parseDuration(item.get("duration").toString()));
                case "RETRY" -> new PipelineStep.RetryStep(
                        Integer.parseInt(item.getOrDefault("maxAttempts", "3").toString()),
                        new BackoffStrategy.Fixed(Duration.ofSeconds(1)),
                        Double.parseDouble(item.getOrDefault("jitter", "0.0").toString()),
                        List.of(Exception.class));
                case "CIRCUIT_BREAKER" -> new PipelineStep.CbStep(
                        Integer.parseInt(item.getOrDefault("failureRateThreshold", "50").toString()),
                        parseDuration(item.getOrDefault("waitDurationOpen", "10s").toString()),
                        Optional.empty());
                case "FALLBACK" -> {
                    String type = item.getOrDefault("type", "THROW").toString();
                    yield new PipelineStep.FallbackStep(
                            PipelineStep.FallbackType.valueOf(type),
                            Optional.ofNullable(item.containsKey("value") ? item.get("value").toString() : null),
                            Optional.ofNullable(item.containsKey("method") ? item.get("method").toString() : null));
                }
                default -> throw new IllegalArgumentException("Unknown step: " + stepName);
            });
        }
        return steps;
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Throwable>[] resolveExceptions(List<String> names) {
        return names.stream().map(name -> {
            try {
                return (Class<? extends Throwable>) Class.forName("java.io." + name);
            } catch (ClassNotFoundException e1) {
                try {
                    return (Class<? extends Throwable>) Class.forName("java.util.concurrent." + name);
                } catch (ClassNotFoundException e2) {
                    return (Class<? extends Throwable>) Exception.class;
                }
            }
        }).toArray(Class[]::new);
    }

    // ── YAML parser ──────────────────────────────────────────────────────────

    private Map<String, Object> parseBlock(List<String> lines, int[] pos, int minIndent) {
        Map<String, Object> result = new LinkedHashMap<>();

        while (pos[0] < lines.size()) {
            String line = lines.get(pos[0]);
            if (line.isBlank() || line.trim().startsWith("#")) { pos[0]++; continue; }

            int indent = indent(line);
            if (indent < minIndent) break;

            String trimmed = line.trim();
            if (trimmed.startsWith("- ")) break;

            int colon = trimmed.indexOf(':');
            if (colon < 0) { pos[0]++; continue; }

            String key = trimmed.substring(0, colon).trim();
            String val = stripComment(trimmed.substring(colon + 1).trim());
            if (val.startsWith("\"") && val.endsWith("\"")) val = val.substring(1, val.length() - 1);

            pos[0]++;

            if (val.isEmpty()) {
                int lookAhead = pos[0];
                while (lookAhead < lines.size() && (lines.get(lookAhead).isBlank() || lines.get(lookAhead).trim().startsWith("#"))) lookAhead++;
                if (lookAhead < lines.size()) {
                    String next = lines.get(lookAhead);
                    int nextIndent = indent(next);
                    if (next.trim().startsWith("- ")) {
                        result.put(key, parseList(lines, pos, nextIndent));
                    } else if (nextIndent > indent) {
                        result.put(key, parseBlock(lines, pos, nextIndent));
                    }
                }
            } else if (val.startsWith("[") && val.endsWith("]")) {
                String inner = val.substring(1, val.length() - 1);
                List<String> items = new ArrayList<>();
                for (String item : inner.split(",")) { String t = item.trim(); if (!t.isEmpty()) items.add(t); }
                result.put(key, items);
            } else {
                result.put(key, val);
            }
        }
        return result;
    }

    private List<Object> parseList(List<String> lines, int[] pos, int minIndent) {
        List<Object> result = new ArrayList<>();

        while (pos[0] < lines.size()) {
            String line = lines.get(pos[0]);
            if (line.isBlank() || line.trim().startsWith("#")) { pos[0]++; continue; }

            int indent = indent(line);
            if (indent < minIndent) break;

            String trimmed = line.trim();
            if (!trimmed.startsWith("- ")) break;

            String content = trimmed.substring(2).trim();
            pos[0]++;

            if (content.contains(":")) {
                Map<String, Object> itemMap = new LinkedHashMap<>();
                int colon = content.indexOf(':');
                String k = content.substring(0, colon).trim();
                String v = stripComment(content.substring(colon + 1).trim());
                if (!v.isEmpty()) itemMap.put(k, v);

                while (pos[0] < lines.size()) {
                    String next = lines.get(pos[0]);
                    if (next.isBlank() || next.trim().startsWith("#")) { pos[0]++; continue; }
                    if (indent(next) <= minIndent || next.trim().startsWith("- ")) break;
                    String t = next.trim();
                    int nc = t.indexOf(':');
                    if (nc >= 0) {
                        String nk = t.substring(0, nc).trim();
                        String nv = stripComment(t.substring(nc + 1).trim());
                        if (!nv.isEmpty()) itemMap.put(nk, nv);
                    }
                    pos[0]++;
                }
                result.add(itemMap);
            } else {
                result.add(content);
            }
        }
        return result;
    }

    private static int indent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }

    private static String stripComment(String s) {
        int idx = s.indexOf(" #");
        return idx >= 0 ? s.substring(0, idx).trim() : s;
    }

    private static Duration parseDuration(String s) {
        return PipelineDslParser.parseDuration(s);
    }
}
