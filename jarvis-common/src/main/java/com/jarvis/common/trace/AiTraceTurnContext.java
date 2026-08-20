package com.jarvis.common.trace;

/**
 * Carries the native tool loop's current turn number across the {@code NativeToolLoopService} ->
 * {@code AIProvider} boundary purely for diagnostic correlation - {@code AIProvider.toolChat(...)}
 * takes no turn parameter (that would be an interface change with a much larger blast radius than
 * this observability feature needs, see {@code NativeToolLoopService}'s own javadoc on this class'
 * usage), so the current turn is threaded through a request-thread-scoped value instead, exactly
 * like {@code com.jarvis.common.diagnostics.InferenceDiagnosticsContext} already does for the
 * request id in this codebase.
 *
 * <p>The native tool loop runs synchronously on one thread for the whole of one {@code
 * NativeToolLoopService#execute} call, so this is safe and always correlates correctly - {@link
 * #clear()} must still be called when the loop finishes (thread pools reuse threads), which {@code
 * NativeToolLoopService#execute} does in a {@code finally} block.</p>
 */
public final class AiTraceTurnContext {

    private static final ThreadLocal<Integer> CURRENT_TURN = new ThreadLocal<>();

    private AiTraceTurnContext() {
    }

    /**
     * Sets the current turn number for this thread.
     *
     * @param turn 1-based turn number
     */
    public static void set(int turn) {
        CURRENT_TURN.set(turn);
    }

    /**
     * Returns the current turn number for this thread, {@code 0} when none is set (e.g. a plain,
     * non-tool-loop model call).
     *
     * @return current turn number, or {@code 0}
     */
    public static int current() {
        Integer turn = CURRENT_TURN.get();
        return turn == null ? 0 : turn;
    }

    /**
     * Clears the current turn number for this thread.
     */
    public static void clear() {
        CURRENT_TURN.remove();
    }
}
