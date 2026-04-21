package io.retrykit;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logging façade for RetryKit. Zero external dependencies — wraps java.util.logging.
 *
 * Enable/disable via:
 *   - System property  : -Dretrykit.logging.enabled=true  (default: true)
 *   - System property  : -Dretrykit.logging.level=DEBUG   (INFO | DEBUG, default: INFO)
 *   - retrykit.yaml    : retrykit.logging.enabled: false
 *   - Programmatic     : RetryKitLogger.setEnabled(false)
 *   - Spring Boot      : logging.level.io.retrykit=OFF     (standard JUL bridge)
 */
public final class RetryKitLogger {

    public enum LogLevel { INFO, DEBUG }

    private static final Logger JUL = Logger.getLogger("io.retrykit");

    private static volatile boolean enabled;
    private static volatile LogLevel level;

    static {
        enabled = Boolean.parseBoolean(System.getProperty("retrykit.logging.enabled", "true"));
        String lvl = System.getProperty("retrykit.logging.level", "INFO").toUpperCase();
        level = lvl.equals("DEBUG") ? LogLevel.DEBUG : LogLevel.INFO;
    }

    private RetryKitLogger() {}

    // ── Configuration ─────────────────────────────────────────────────────────

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static void setLevel(LogLevel l) {
        level = l;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static LogLevel getLevel() {
        return level;
    }

    // ── Log methods ───────────────────────────────────────────────────────────

    public static void info(String msg) {
        if (enabled) JUL.info(msg);
    }

    public static void warn(String msg) {
        if (enabled) JUL.warning(msg);
    }

    public static void error(String msg) {
        if (enabled) JUL.severe(msg);
    }

    /** Only logged when level=DEBUG */
    public static void debug(Supplier<String> msg) {
        if (enabled && level == LogLevel.DEBUG) JUL.fine(msg);
    }

    public static void debug(String msg) {
        if (enabled && level == LogLevel.DEBUG) JUL.fine(msg);
    }
}
