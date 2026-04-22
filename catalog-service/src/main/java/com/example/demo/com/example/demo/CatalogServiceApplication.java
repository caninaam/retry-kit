package com.example.demo.com.example.demo;

import com.example.demo.com.example.demo.entity.Order;
import com.example.demo.com.example.demo.repository.OrderRepository;
import io.github.caninaam.retrykit.RetryKit;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SpringBootApplication
@RestController
@RequestMapping("/orders")
public class CatalogServiceApplication {

    @Autowired
    private OrderRepository orderRepository;

    // RetryKit instance — calls a fictitious pricing service with retry
    private final RetryKit<Double> pricingKit = RetryKit.<Double>retry()
            .maxAttempts(3)
            .waitDuration(Duration.ofMillis(200))
            .fallback(ctx -> -1.0)
            .build();

    private final AtomicInteger pricingCalls = new AtomicInteger(0);

    @PostConstruct
    public void initOrdersTable() {
        orderRepository.saveAll(Stream.of(
                new Order("mobile",        "electronics", "white", 20000),
                new Order("T-Shirt",       "clothes",     "black",   999),
                new Order("Jeans",         "clothes",     "blue",   1999),
                new Order("Laptop",        "electronics", "gray",  50000),
                new Order("digital watch", "electronics", "black",  2500),
                new Order("Fan",           "electronics", "black", 50000)
        ).collect(Collectors.toList()));
    }

    // ── Standard endpoints ────────────────────────────────────────────────────

    @GetMapping
    public List<Order> getOrders() {
        applySimulation();
        return orderRepository.findAll();
    }

    @GetMapping("/{category}")
    public List<Order> getOrdersByCategory(@PathVariable String category) {
        applySimulation();
        return orderRepository.findByCategory(category);
    }

    // ── RetryKit example — orders enriched with pricing ───────────────────────
    // GET /orders/with-pricing
    // Calls a fictitious pricing service (fails 2 out of 3 times) with retry.

    @GetMapping("/with-pricing")
    public List<Order> getOrdersWithPricing() throws Exception {
        List<Order> orders = orderRepository.findAll();
        for (Order order : orders) {
            Double factor = pricingKit.call(() -> fetchPricingFactor(), "GET /pricing");
            if (factor > 0) order.setPrice(order.getPrice() * factor);
        }
        return orders;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void applySimulation() {
        if (SimulationController.slowRemaining.getAndDecrement() > 0) {
            try { Thread.sleep(SimulationController.slowMs); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (SimulationController.failRemaining.getAndDecrement() > 0) {
            throw new RuntimeException("Simulated failure");
        }
    }

    private Double fetchPricingFactor() {
        int call = pricingCalls.incrementAndGet();
        if (call % 3 != 0) throw new RuntimeException("Pricing service unavailable");
        return 1.1;
    }

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
