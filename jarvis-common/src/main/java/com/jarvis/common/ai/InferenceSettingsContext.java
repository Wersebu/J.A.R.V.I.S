package com.jarvis.common.ai;

/** Provider-resolved settings used by admission without depending on provider classes. */
public final class InferenceSettingsContext {
    public record Settings(String profile, int priority, boolean background, long deadlineNanos,
                           String model,int physicalContext,int inputBudget) {
        public Settings(String profile,int priority,boolean background,long deadlineNanos){this(profile,priority,background,deadlineNanos,"",0,0);}
    }
    private static final ThreadLocal<Settings> CURRENT = new ThreadLocal<>();
    private InferenceSettingsContext() {}
    public static Settings current() { return CURRENT.get(); }
    public static AutoCloseableScope open(Settings value) {
        Settings previous = CURRENT.get(); CURRENT.set(value);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public interface AutoCloseableScope extends AutoCloseable { @Override void close(); }
}
