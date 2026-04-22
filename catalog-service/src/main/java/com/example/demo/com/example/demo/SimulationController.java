package com.example.demo.com.example.demo;

import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Endpoints to simulate failures for testing RetryKit in user-service.
 *
 * GET /simulate/status          → current counters
 * POST /simulate/fail?times=N   → next N calls to /orders will fail with 500
 * POST /simulate/slow?ms=N      → next N calls will sleep N ms (timeout testing)
 * POST /simulate/reset          → reset all counters
 */
@RestController
@RequestMapping("/simulate")
public class SimulationController {

    static final AtomicInteger failRemaining = new AtomicInteger(0);
    static final AtomicInteger slowRemaining = new AtomicInteger(0);
    static volatile long slowMs = 0;

    @PostMapping("/fail")
    public Map<String, Object> setFail(@RequestParam(defaultValue = "3") int times) {
        failRemaining.set(times);
        return Map.of("failRemaining", times);
    }

    @PostMapping("/slow")
    public Map<String, Object> setSlow(
            @RequestParam(defaultValue = "3") int times,
            @RequestParam(defaultValue = "2000") long ms) {
        slowRemaining.set(times);
        slowMs = ms;
        return Map.of("slowRemaining", times, "delayMs", ms);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        failRemaining.set(0);
        slowRemaining.set(0);
        slowMs = 0;
        return Map.of("status", "reset");
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "failRemaining", failRemaining.get(),
                "slowRemaining", slowRemaining.get(),
                "slowMs", slowMs
        );
    }
}
