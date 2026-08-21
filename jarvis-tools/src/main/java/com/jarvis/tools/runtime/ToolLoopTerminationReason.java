package com.jarvis.tools.runtime;

/**
 * Why one native tool-loop execution stopped, decided from the loop's own real state (turn/timeout
 * budget, retry counters, tool results) rather than parsed out of any model-written text. Every
 * {@link ToolCallingResult} carries one of these via {@link ToolLoopTerminationInfo}, so a caller
 * (diagnostics, logging, the chat pipeline, the desktop client) can explain what actually happened
 * without re-deriving it from a blank/garbled final answer.
 */
public enum ToolLoopTerminationReason {

    /** The loop produced a real, verified final answer; nothing further was needed. */
    COMPLETED,
    /** The configured turn/call budget ({@code max-calls-fast}/{@code max-calls-research}) ran out. */
    MAX_TURNS_REACHED,
    /** The loop's own wall-clock budget ({@code timeout-seconds}) was exceeded. */
    TIMEOUT,
    /** The bounded consecutive-tool-failure limit was exhausted. */
    MAX_FAILURES_REACHED,
    /** The same tool operation was blocked as a repeat until the loop ran out of budget. */
    MAX_OPERATION_REPEATS_REACHED,
    /** The model returned neither a tool call nor any text content, repeatedly. */
    EMPTY_MODEL_RESPONSE,
    /** The AI provider itself failed (e.g. malformed tool-call JSON) and no safe recovery text existed. */
    PROVIDER_FAILURE,
    /** Every executed tool call this loop was an MCP call that failed, with no successful call at all. */
    MCP_FAILURE,
    /** The loop stopped because a proposed change is waiting for the user's approval. */
    WAITING_FOR_APPROVAL,
    /** The loop stopped (retries/budget exhausted) with the goal contract still not satisfied. */
    INCOMPLETE_GOAL,
    /** The tool-less final-synthesis turn itself asked for more tools instead of answering. */
    FINAL_SYNTHESIS_REQUESTED_MORE_TOOLS,
    /** No other reason could be determined from the available state. */
    UNKNOWN
}
