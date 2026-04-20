package com.example.demo.com.example.demo;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import com.example.demo.com.example.demo.dto.OrderDTO;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.retrykit.RetryKit;
import io.retrykit.WorkflowMode;

@SpringBootApplication
@RestController
@RequestMapping("/user-service")
public class UserServiceApplication {

    @Autowired
    @Lazy
    private RestTemplate restTemplate;

    public static final String USER_SERVICE = "userService";
    private static final String BASEURL = "http://localhost:8080/orders";

    // ── Instances partagées — CB persiste entre les requêtes ─────────────────
    // IMPORTANT : créer en champ, pas dans la méthode HTTP.
    // Si recréé à chaque requête → nouveau CB → état perdu.

    private final RetryKit<List<OrderDTO>> retryFirstKit = RetryKit.<List<OrderDTO>>retry()
            .mode(WorkflowMode.RETRY_FIRST)
            .maxAttempts(2)
            .waitDuration(Duration.ofSeconds(20))  // 3 tentatives × 3s = ~9s visible quand CB CLOSED
            .circuitBreaker(cb -> cb
                    .failureRateThreshold(50)
                    .minimumNumberOfCalls(5)
                    .waitDurationInOpenState(Duration.ofMinutes(3)) // OPEN 30min → KO < 10ms
                    .permittedCallsInHalfOpen(2))
            .fallback(ctx -> fallbackOrders())
            .build();

    @Value("${retrykit.config.path:retrykit.yaml}")
    private String retrykitConfigPath;

    @Value("${retrykit.pipeline.dsl:TIMEOUT(3s) > RETRY(3) > CB(50%)}")
    private String pipelineDsl;

    @Value("${retrykit.logging.enabled:true}")
    private boolean retrykitLoggingEnabled;

    @Value("${retrykit.logging.level:INFO}")
    private String retrykitLoggingLevel;

    private RetryKit<List<OrderDTO>> pipelineKit;

    // Shared kits per profile — CB state persists across requests
    private final Map<String, RetryKit<List<OrderDTO>>> profileKits = new java.util.concurrent.ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        io.retrykit.RetryKitLogger.setEnabled(retrykitLoggingEnabled);
        io.retrykit.RetryKitLogger.setLevel("DEBUG".equalsIgnoreCase(retrykitLoggingLevel)
                ? io.retrykit.RetryKitLogger.LogLevel.DEBUG
                : io.retrykit.RetryKitLogger.LogLevel.INFO);
        pipelineKit = buildPipelineKit();
    }

    private final RetryKit<List<OrderDTO>> cbFirstKit = RetryKit.<List<OrderDTO>>retry()
            .mode(WorkflowMode.CB_FIRST)
            .maxAttempts(2)
            .waitDuration(Duration.ofMillis(500))
            .circuitBreaker(cb -> cb
                    .failureRateThreshold(30)
                    .minimumNumberOfCalls(3)
                    .waitDurationInOpenState(Duration.ofMinutes(30)) // OPEN 30min → KO direct
                    .timeout(Duration.ofSeconds(2)))
            .fallback(ctx -> fallbackOrders())
            .build();

    // ── Resilience4j (référence) ─────────────────────────────────────────────

    @GetMapping("/displayOrders")
    @CircuitBreaker(name = USER_SERVICE)
    @Retry(name = USER_SERVICE, fallbackMethod = "getAllAvailableProducts")
    public List<OrderDTO> displayOrders(
            @RequestParam(value = "category", required = false) String category) {
        return fetchOrders(category);
    }

    // ── RetryKit : cas 1 — retry fixe ───────────────────────────────────────

    @GetMapping("/orders/simple-retry")
    public List<OrderDTO> simpleRetry(
            @RequestParam(value = "category", required = false) String category) throws Exception {

        return RetryKit.<List<OrderDTO>>retry()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(2))
                .fallback(ctx -> fallbackOrders())
                .call(() -> fetchOrders(category));
    }

    // ── RetryKit : cas 2 — backoff exponentiel ───────────────────────────────

    @GetMapping("/orders/exponential")
    public List<OrderDTO> exponentialBackoff(
            @RequestParam(value = "category", required = false) String category) throws Exception {

        return RetryKit.<List<OrderDTO>>retry()
                .maxAttempts(4)
                .exponentialBackoff(Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10))
                .fallback(ctx -> fallbackOrders())
                .call(() -> fetchOrders(category));
    }

    // ── RetryKit : cas 3 — jitter ────────────────────────────────────────────

    @GetMapping("/orders/jitter")
    public List<OrderDTO> withJitter(
            @RequestParam(value = "category", required = false) String category) throws Exception {

        return RetryKit.<List<OrderDTO>>retry()
                .maxAttempts(3)
                .exponentialBackoff(Duration.ofMillis(500), 2.0)
                .withJitter(0.2)
                .fallback(ctx -> fallbackOrders())
                .call(() -> fetchOrders(category));
    }

    // ── RetryKit : cas 4 — retryOn exception spécifique ─────────────────────

    @GetMapping("/orders/retry-on")
    public List<OrderDTO> retryOnSpecificException(
            @RequestParam(value = "category", required = false) String category) throws Exception {

        return RetryKit.<List<OrderDTO>>retry()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(1))
                .retryOn(
                        org.springframework.web.client.ResourceAccessException.class,
                        org.springframework.web.client.HttpServerErrorException.class)
                .fallback(ctx -> fallbackOrders())
                .call(() -> fetchOrders(category));
    }

    // ── RetryKit : cas 5 — RETRY_FIRST (instance partagée) ──────────────────

    @GetMapping("/orders/retry-first")
    public List<OrderDTO> retryFirst(
            @RequestParam(value = "category", required = false) String category) throws Exception {


        return retryFirstKit.call(() -> fetchOrders(category));
    }

    // ── RetryKit : cas 6 — CB_FIRST (instance partagée) ─────────────────────

    @GetMapping("/orders/cb-first")
    public List<OrderDTO> cbFirst(
            @RequestParam(value = "category", required = false) String category) throws Exception {


        return cbFirstKit.call(() -> fetchOrders(category));
    }

    // ── RetryKit : cas 7 — Pipeline DSL ─────────────────────────────────────

    @GetMapping("/orders/pipeline")
    public List<OrderDTO> pipeline(
            @RequestParam(value = "category", required = false) String category) throws Exception {

        return pipelineKit.call(() -> fetchOrders(category));
    }

    // ── RetryKit : cas 8 — maxDuration ──────────────────────────────────────

    @GetMapping("/orders/max-duration")
    public List<OrderDTO> maxDuration(
            @RequestParam(value = "category", required = false) String category) throws Exception {

        return RetryKit.<List<OrderDTO>>retry()
                .maxAttempts(10)
                .waitDuration(Duration.ofSeconds(2))
                .maxDuration(Duration.ofSeconds(5))  // stop after 5s total, regardless of attempts
                .fallback(ctx -> fallbackOrders())
                .call(() -> fetchOrders(category));
    }

    // ── RetryKit : cas 10 — Async ────────────────────────────────────────────

    @GetMapping("/orders/async")
    public CompletableFuture<List<OrderDTO>> async(
            @RequestParam(value = "category", required = false) String category) {

        return RetryKit.<List<OrderDTO>>retry()
                .maxAttempts(3)
                .waitDuration(Duration.ofSeconds(1))
                .fallback(ctx -> fallbackOrders())
                .callAsync(() -> fetchOrders(category));
    }

    // ── RetryKit : pipeline dynamique — charge n'importe quel profil YAML ───
    // GET /user-service/orders/pipeline/pipeline-exponential?category=electronics

    @GetMapping("/orders/pipeline/{profile}")
    public List<OrderDTO> pipelineProfile(
            @PathVariable String profile,
            @RequestParam(value = "category", required = false) String category) throws Exception {

        RetryKit<List<OrderDTO>> kit = profileKits.computeIfAbsent(profile, p -> {
            try {
                return RetryKit.<List<OrderDTO>>fromYaml(retrykitConfigPath)
                        .profile(p)
                        .<List<OrderDTO>>as()
                        .fallback(ctx -> fallbackOrders())
                        .build();
            } catch (Exception e) {
                throw new RuntimeException("Profile not found: " + p, e);
            }
        });
        return kit.call(() -> fetchOrders(category));
    }

    // ── Statut CB — observer sans faire d'appel ──────────────────────────────

    /**
     * Voir l'état des circuit breakers sans appeler catalog-service.
     * GET /user-service/cb-status
     */
    @GetMapping("/cb-status")
    public Map<String, String> cbStatus() {
        Map<String, String> status = new LinkedHashMap<>();
        status.put("retry-first", retryFirstKit.circuitBreakerState()
                .map(Enum::name).orElse("NO_CB"));
        status.put("cb-first", cbFirstKit.circuitBreakerState()
                .map(Enum::name).orElse("NO_CB"));
        status.put("pipeline", pipelineKit.circuitBreakerState()
                .map(Enum::name).orElse("NO_CB"));
        return status;
    }

    // ── Logging control ──────────────────────────────────────────────────────
    // GET  /user-service/logging          → current state
    // POST /user-service/logging?enabled=true&level=DEBUG

    @GetMapping("/logging")
    public Map<String, String> loggingStatus() {
        Map<String, String> status = new LinkedHashMap<>();
        status.put("enabled", String.valueOf(io.retrykit.RetryKitLogger.isEnabled()));
        status.put("level",   io.retrykit.RetryKitLogger.getLevel().name());
        return status;
    }

    @org.springframework.web.bind.annotation.PostMapping("/logging")
    public Map<String, String> loggingUpdate(
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String level) {
        if (enabled != null) io.retrykit.RetryKitLogger.setEnabled(enabled);
        if (level   != null) io.retrykit.RetryKitLogger.setLevel(
                "DEBUG".equalsIgnoreCase(level)
                        ? io.retrykit.RetryKitLogger.LogLevel.DEBUG
                        : io.retrykit.RetryKitLogger.LogLevel.INFO);
        return loggingStatus();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RetryKit<List<OrderDTO>> buildPipelineKit() {
        try {
            return RetryKit.<List<OrderDTO>>fromYaml(retrykitConfigPath)
                    .profile("pipeline")
                    .<List<OrderDTO>>as()
                    .fallback(ctx -> fallbackOrders())
                    .withHotReload(Duration.ofSeconds(5))
                    .build();
        } catch (Exception e) {
            System.err.printf("[pipeline] YAML load failed (%s), using DSL from properties: %s%n",
                    e.getMessage(), pipelineDsl);
            return RetryKit.<List<OrderDTO>>retry()
                    .pipeline(pipelineDsl)
                    .fallback(ctx -> fallbackOrders())
                    .build();
        }
    }

    private List<OrderDTO> fetchOrders(String category) {
        String url = (category == null || category.isBlank())
                ? BASEURL : BASEURL + "/" + category;
        return restTemplate.exchange(url, HttpMethod.GET, null,
                new ParameterizedTypeReference<List<OrderDTO>>() {}).getBody();
    }

    private List<OrderDTO> fallbackOrders() {
        return Stream.of(
                new OrderDTO(119, "DEFAULT",           "electronics", "white",         45000) ).collect(Collectors.toList());
    }

    public List<OrderDTO> getAllAvailableProducts(String category, Exception e) {
        return fallbackOrders();
    }

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
