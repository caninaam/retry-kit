package io.retrykit;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class HotReloadWatcher {

    private final Path filePath;
    private final Duration checkInterval;
    private final Runnable onChanged;
    private volatile boolean running = false;
    private Thread watchThread;

    public HotReloadWatcher(String yamlPath, Duration checkInterval, Runnable onChanged) {
        this.filePath = Path.of(yamlPath).toAbsolutePath();
        this.checkInterval = checkInterval;
        this.onChanged = onChanged;
    }

    public void start() {
        running = true;
        watchThread = new Thread(this::watchLoop, "retrykit-hotreload");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    public void stop() {
        running = false;
        if (watchThread != null) watchThread.interrupt();
    }

    private void watchLoop() {
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            filePath.getParent().register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

            while (running) {
                WatchKey key = watchService.poll(checkInterval.toMillis(), TimeUnit.MILLISECONDS);
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = (Path) event.context();
                    if (filePath.getFileName().equals(changed)) {
                        try {
                            onChanged.run();
                        } catch (Exception e) {
                            // Reload failed — keep running with previous config
                        }
                    }
                }
                key.reset();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // WatchService unavailable — hot reload disabled silently
        }
    }
}
