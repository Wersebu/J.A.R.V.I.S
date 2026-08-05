package com.jarvis.tools.runtime;

/**
 * Native tool-calling runtime states.
 */
public enum ToolLoopState {
    ANALYZING_REQUEST,
    SELECTING_TOOL,
    VALIDATING_TOOL_CALL,
    WAITING_FOR_APPROVAL,
    EXECUTING_TOOL,
    OBSERVING_RESULT,
    DECIDING_NEXT_STEP,
    VERIFYING_RESULT,
    READY_TO_ANSWER,
    FINISHED,
    FAILED
}
