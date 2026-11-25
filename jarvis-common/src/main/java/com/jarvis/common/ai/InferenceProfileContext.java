package com.jarvis.common.ai;

import java.util.function.Supplier;

/** Explicit operation selection, scoped to the synchronous inference subtree. */
public final class InferenceProfileContext {
    private static final ThreadLocal<String> PROFILE = new ThreadLocal<>();
    private InferenceProfileContext() {}
    public static String current() { return PROFILE.get(); }
    public static <T> T call(String profile, Supplier<T> work) {
        String previous = PROFILE.get();
        PROFILE.set(profile);
        try { return work.get(); }
        finally { if (previous == null) PROFILE.remove(); else PROFILE.set(previous); }
    }
}
