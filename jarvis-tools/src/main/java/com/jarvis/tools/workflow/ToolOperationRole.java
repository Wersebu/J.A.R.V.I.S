package com.jarvis.tools.workflow;

/**
 * General-purpose classification of what a tool call actually accomplishes, independent of any
 * specific tool catalog (native Jarvis tools and MCP-provided tools alike). Used to tell a
 * bootstrap/preparatory call (finding out what is available, picking one of several options) apart
 * from a call that genuinely delivers the information or action the user asked for - so a
 * successful bootstrap call is never mistaken for task completion.
 */
public enum ToolOperationRole {

    /** Enumerates available sessions/connections/instances/resources (e.g. list open Studios). */
    DISCOVERY,
    /** Picks one of several already-discovered options to act on (e.g. set the active instance). */
    SELECTION,
    /** Retrieves a specific, already-identified piece of information. */
    READ,
    /** Looks for information matching a query across a larger structure. */
    SEARCH,
    /** Examines one specific object/entity in detail. */
    INSPECT,
    /** Checks or validates something against a rule or expectation. */
    VERIFY,
    /** Creates, modifies, or removes state. */
    WRITE,
    /** Performs an action with a real-world or in-app effect beyond reading/writing data. */
    EXECUTE,
    /** No pattern matched; treated as non-bootstrap so it never blocks completion by itself. */
    UNKNOWN;

    /**
     * Bootstrap/preparatory roles never represent a finished answer to the user's actual goal on
     * their own - only {@link #DISCOVERY} and {@link #SELECTION} qualify.
     *
     * @return true when this role is bootstrap-only
     */
    public boolean isBootstrap() {
        return this == DISCOVERY || this == SELECTION;
    }
}
