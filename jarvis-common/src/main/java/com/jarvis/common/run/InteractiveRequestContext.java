package com.jarvis.common.run;

/** Identity and ingress reservation shared by transport, pipeline and cancellation. */
public final class InteractiveRequestContext {
    public record Request(String requestId, ConversationTurns.Turn turn, Runnable onRunning) {}
    private static final ThreadLocal<Request> CURRENT = new ThreadLocal<>();
    private InteractiveRequestContext() {}
    public static Request current() { return CURRENT.get(); }
    public static void run(Request request, Runnable action) {
        Request previous = CURRENT.get(); CURRENT.set(request);
        try { action.run(); } finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
}
