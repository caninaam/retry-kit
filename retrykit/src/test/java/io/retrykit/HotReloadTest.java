package io.retrykit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HotReloadTest {

    @TempDir
    Path tempDir;

    @Test
    void watcherFiresCallbackOnFileChange() throws Exception {
        Path yaml = tempDir.resolve("retrykit.yaml");
        Files.writeString(yaml, "# initial");

        AtomicBoolean reloaded = new AtomicBoolean(false);
        HotReloadWatcher watcher = new HotReloadWatcher(
                yaml.toString(), Duration.ofMillis(100), () -> reloaded.set(true));
        watcher.start();

        Thread.sleep(200); // let watcher register
        Files.writeString(yaml, "# modified");

        waitFor(reloaded, 3000);
        watcher.stop();

        assertTrue(reloaded.get(), "Callback should have fired after file change");
    }

    @Test
    void watcherDoesNotFireBeforeChange() throws Exception {
        Path yaml = tempDir.resolve("retrykit.yaml");
        Files.writeString(yaml, "# initial");

        AtomicBoolean reloaded = new AtomicBoolean(false);
        HotReloadWatcher watcher = new HotReloadWatcher(
                yaml.toString(), Duration.ofMillis(100), () -> reloaded.set(true));
        watcher.start();

        Thread.sleep(300); // wait without changing file
        watcher.stop();

        assertFalse(reloaded.get(), "Callback should NOT fire when file is unchanged");
    }

    @Test
    void watcherStopsCleanly() throws Exception {
        Path yaml = tempDir.resolve("retrykit.yaml");
        Files.writeString(yaml, "# initial");

        AtomicInteger callCount = new AtomicInteger(0);
        HotReloadWatcher watcher = new HotReloadWatcher(
                yaml.toString(), Duration.ofMillis(100), callCount::incrementAndGet);
        watcher.start();
        watcher.stop();

        // Modify after stop — callback should NOT fire
        Thread.sleep(200);
        Files.writeString(yaml, "# after stop");
        Thread.sleep(300);

        assertEquals(0, callCount.get(), "No callbacks should fire after stop");
    }

    @Test
    void hotReloadUpdatesEngineOnFileChange() throws Exception {
        // Profile starts with maxAttempts=1, then reloaded with maxAttempts=3
        Path yaml = tempDir.resolve("retrykit.yaml");
        Files.writeString(yaml, """
                retrykit:
                  profiles:
                    default:
                      mode: RETRY_FIRST
                      retry:
                        maxAttempts: 1
                """);

        AtomicInteger calls = new AtomicInteger(0);

        RetryKit<String> kit = RetryKit.<String>fromYaml(yaml.toString())
                .profile("default")
                .<String>as()
                .fallback(ctx -> "fallback")
                .withHotReload(Duration.ofMillis(100))
                .build();

        // With maxAttempts=1, 1 call before fallback
        kit.call(() -> { calls.incrementAndGet(); throw new RuntimeException("fail"); });
        assertEquals(1, calls.get());

        // Update YAML to maxAttempts=3
        Files.writeString(yaml, """
                retrykit:
                  profiles:
                    default:
                      mode: RETRY_FIRST
                      retry:
                        maxAttempts: 3
                """);

        waitFor(() -> {
            calls.set(0);
            try {
                kit.call(() -> { calls.incrementAndGet(); throw new RuntimeException("fail"); });
            } catch (Exception ignored) {}
            return calls.get() >= 3;
        }, 3000);

        kit.stop();
        assertEquals(3, calls.get(), "After reload, should retry 3 times");
    }

    private void waitFor(AtomicBoolean flag, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!flag.get() && System.currentTimeMillis() < deadline) Thread.sleep(100);
    }

    private void waitFor(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            try { if (condition.get()) return; } catch (Exception ignored) {}
            Thread.sleep(200);
        }
    }

    @FunctionalInterface
    interface BooleanSupplier { boolean get() throws Exception; }
}
