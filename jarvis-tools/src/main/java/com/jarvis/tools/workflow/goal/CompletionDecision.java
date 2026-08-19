package com.jarvis.tools.workflow.goal;

/**
 * Outcome of a {@link GoalCompletionVerifier} check - deliberately a closed three-way decision, not
 * a boolean, so "cannot proceed" (missing tool, unrecoverable tool error, missing user-supplied
 * data) is represented distinctly from "not done yet, keep going".
 */
public enum CompletionDecision {

    /** The proposed final answer genuinely satisfies the goal contract; safe to return to the user. */
    COMPLETE,
    /** Not satisfied yet, but continuing the bounded tool loop can plausibly close the gap. */
    CONTINUE,
    /**
     * Not satisfied and no safe automatic continuation exists - reserved for one of: no suitable
     * tool exists for what is still missing, a tool call failed in a way that blocks progress, or
     * the missing piece can only come from the user. Never used as a substitute for an ordinary
     * retry; {@link #CONTINUE} covers every case where the bounded loop can still make progress on
     * its own.
     */
    BLOCKED
}
