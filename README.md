# RetryKit

[![CI](https://github.com/caninaam/retry-kit/actions/workflows/ci.yml/badge.svg)](https://github.com/caninaam/retry-kit/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.caninaam/retrykit)](https://central.sonatype.com/artifact/io.github.caninaam/retrykit)

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
  <groupId>io.github.caninaam</groupId>
  <artifactId>retrykit</artifactId>
  <version>1.0.1</version>
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

## Example projects

This repository includes two Spring Boot services demonstrating RetryKit in a real microservice architecture:

```
retrykit/          ← the library
user-service/      ← client (port 8081) — calls catalog-service with RetryKit retry/CB
catalog-service/   ← server (port 8080) — serves orders, includes simulation endpoints
```

### Architecture

```
user-service (8081)  →  catalog-service (8080)  →  [pricing service - simulated]
      ↑ RetryKit                                          ↑ RetryKit
```

### Run locally

```bash
# Terminal 1 — start catalog-service
cd catalog-service && ./mvnw spring-boot:run

# Terminal 2 — start user-service
cd user-service && ./mvnw spring-boot:run
```

### Simulate failures (catalog-service)

```bash
# Make next 5 calls to /orders fail with 500
POST http://localhost:8080/simulate/fail?times=5

# Make next 3 calls slow (2s delay) — triggers timeout in user-service
POST http://localhost:8080/simulate/slow?times=3&ms=2000

# Reset all simulation counters
POST http://localhost:8080/simulate/reset

# Check current simulation state
GET  http://localhost:8080/simulate/status
```

### Test RetryKit endpoints (user-service)

```bash
GET http://localhost:8081/user-service/orders/simple-retry     # fixed delay retry
GET http://localhost:8081/user-service/orders/exponential      # exponential backoff
GET http://localhost:8081/user-service/orders/retry-first      # RETRY_FIRST + CB
GET http://localhost:8081/user-service/orders/cb-first         # CB_FIRST + CB
GET http://localhost:8081/user-service/orders/pipeline         # Pipeline DSL
GET http://localhost:8081/user-service/cb-status               # CB state observer
```

### RetryKit in catalog-service

```bash
# Orders enriched with pricing — RetryKit retries the pricing service (fails 2/3 times)
GET http://localhost:8080/orders/with-pricing
```

---

## License

MIT
