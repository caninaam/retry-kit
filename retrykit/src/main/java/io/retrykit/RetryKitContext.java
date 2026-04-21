package io.retrykit;

public final class RetryKitContext {

    private static final ThreadLocal<String> current = new ThreadLocal<>();

    private RetryKitContext() {}

    public static void set(String label) { current.set(label); }
    public static String get() { String v = current.get(); return v != null ? v : ""; }
    public static void clear() { current.remove(); }
}
