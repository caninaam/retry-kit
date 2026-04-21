# RetryKit

Lightweight Java 17 retry & circuit breaker library.  
Zero dependencies · Fluent API · Pipeline DSL · YAML config with hot reload · JAR < 50 KB

---

## Why RetryKit?

| Feature | RetryKit | Resilience4j |
|---|---|---|
| Dependencies | **0** | ~10 |
| JAR size | **< 50 KB** | ~500 KB |
| Pipeline DSL | **yes** | no |
| YAML hot reload | **yes** | no |
| Learning curve | **5 min** | 30 min+ |

---

## Quick start

```xml
<dependency>
  <groupId>io.retrykit</groupId>
  <artifactId>retrykit</artifactId>
  <version>0.1.1-SNAPSHOT</version>
</dependency>
```

```java
String result = RetryKit.<String>retry()
    .maxAttempts(3)
    .waitDuration(Duration.ofSeconds(1))
    .fallback(ctx -> "default")
    .call(() -> myService.call());
```

---

## Features

### Retry strategies

```java
// Fixed delay
RetryKit.<String>retry()
    .maxAttempts(3)
    .waitDuration(Duration.ofSeconds(1))
    .call(() -> myService.call());

// Exponential backoff
RetryKit.<String>retry()
    .maxAttempts(4)
    .exponentialBackoff(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10))
    .call(() -> myService.call());

// With jitter (avoids thundering herd)
RetryKit.<String>retry()
    .maxAttempts(3)
    .exponentialBackoff(Duration.ofMillis(500), 2.0)
    .withJitter(0.2)
    .call(() -> myService.call());

// Max total duration
RetryKit.<String>retry()
    .maxAttempts(10)
    .waitDuration(Duration.ofSeconds(2))
    .maxDuration(Duration.ofSeconds(5))
    .call(() -> myService.call());

// Retry only on specific exceptions
RetryKit.<String>retry()
    .maxAttempts(3)
    .retryOn(IOException.class, HttpServerErrorException.class)
    .call(() -> myService.call());

// Retry on result predicate
RetryKit.<String>retry()
    .maxAttempts(4)
    .retryIf(result -> "retry-me".equals(result))
    .call(() -> myService.call());
```

### Circuit Breaker

```java
// RETRY_FIRST: retry → then check CB
RetryKit.<String>retry()
    .mode(WorkflowMode.RETRY_FIRST)
    .maxAttempts(3)
    .waitDuration(Duration.ofSeconds(1))
    .circuitBreaker(cb -> cb
        .failureRateThreshold(50)       // open if 50%+ failures
        .minimumNumberOfCalls(5)        // need 5 calls before evaluating
        .waitDurationInOpenState(Duration.ofMinutes(1))
        .permittedCallsInHalfOpen(2))
    .fallback(ctx -> "fallback")
    .build();

// CB_FIRST: check CB → then retry
RetryKit.<String>retry()
    .mode(WorkflowMode.CB_FIRST)
    ...
```

### Pipeline DSL

Compose steps in order — leftmost = outermost wrapper:

```java
RetryKit.<String>retry()
    .pipeline("TIMEOUT(3s) > RETRY(3) > CB(50%)")
    .call(() -> myService.call());
```

Full DSL syntax:

```
TIMEOUT(5s)
RETRY(3)
RETRY(maxAttempts:5, waitDuration:500ms)
RETRY(maxAttempts:4, waitDuration:1s, backoff:2.0, maxWait:10s, jitter:0.2)
CB(50%)
CB(failureRate:50%, minCalls:5, wait:1m, halfOpen:2, timeout:2s)
```

### YAML config

```yaml
# retrykit.yaml
default:
  mode: RETRY_FIRST
  maxAttempts: 3
  waitDuration: PT1S
  circuitBreaker:
    failureRateThreshold: 50
    minimumNumberOfCalls: 5
    waitDurationInOpenState: PT1M

pipeline:
  mode: PIPELINE
  pipeline: "TIMEOUT(3s) > RETRY(3) > CB(50%)"
```

```java
RetryKit.<String>fromYaml("retrykit.yaml")
    .profile("default")
    .<String>as()
    .fallback(ctx -> "fallback")
    .build();
```

### Hot reload

Config reloads without restart — the YAML file must be **outside the JAR**:

```java
RetryKit.<String>fromYaml("/etc/myapp/retrykit.yaml")
    .profile("pipeline")
    .<String>as()
    .withHotReload(Duration.ofSeconds(5))   // checks every 5s
    .fallback(ctx -> "fallback")
    .build();
```

### Async

```java
CompletableFuture<String> future = RetryKit.<String>retry()
    .maxAttempts(3)
    .callAsync(() -> myService.call());
```

### Labeled calls (for logging)

```java
kit.call(() -> myService.call(), "GET /orders");
// logs: [RetryKit] [GET /orders] Attempt 1/3 FAILED
```

---

## Exception semantics

| Exception | Meaning |
|---|---|
| `RetryException` | Service was called, all attempts failed |
| `CircuitBreakerOpenException` | CB is OPEN — service not called at all |
| Original exception | Non-retryable exception, propagated as-is |

```java
try {
    kit.call(() -> myService.call());
} catch (RetryException e) {
    // e.attempts() = number of attempts made
} catch (CircuitBreakerOpenException e) {
    // service known to be down, not called
}
```

---

## Observe circuit breaker state

```java
kit.circuitBreakerState(); // Optional<CircuitBreaker.State>
// → CLOSED | OPEN | HALF_OPEN
```

---

## Requirements

- Java 17+
- No runtime dependencies

---

## License

MIT
