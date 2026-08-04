package com.jarvis.common.diagnostics;

/**
 * Thread-bound access to active inference diagnostics.
 */
public final class InferenceDiagnosticsContext {

    private static final ThreadLocal<InferenceDiagnostics> ACTIVE = new ThreadLocal<>();

    private InferenceDiagnosticsContext() {
    }

    /**
     * Binds diagnostics to the current processing thread.
     *
     * @param diagnostics diagnostics object
     */
    public static void bind(InferenceDiagnostics diagnostics) {
        ACTIVE.set(diagnostics);
    }

    /**
     * Returns active diagnostics for the current thread.
     *
     * @return diagnostics or null
     */
    public static InferenceDiagnostics current() {
        return ACTIVE.get();
    }

    /**
     * Clears current thread diagnostics.
     */
    public static void clear() {
        ACTIVE.remove();
    }
}
