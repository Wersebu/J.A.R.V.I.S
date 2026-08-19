package com.jarvis.tools.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies a tool call's {@link ToolOperationRole} from its tool/operation names alone, using
 * generic word-token heuristics - never a hardcoded per-tool or per-server catalog.
 *
 * <p>This must work uniformly for native Jarvis tools (e.g. {@code storedataset.GET_DATASET}) and
 * for MCP-provided tools, whose real semantic action lives entirely inside the tool name rather
 * than the operation (every native MCP call is exposed as {@code mcp_<server>_<tool>__call}, so
 * {@code operation} is always literally {@code CALL} - see {@code McpToolDescriptor#jarvisToolName()}
 * and {@code NativeToolSchemaMapper#toAction}). Classification therefore runs against the
 * concatenation of both names, so the MCP tool name's own words (e.g. {@code list_roblox_studios},
 * {@code search_game_tree}, {@code set_active_studio}) still drive the result.</p>
 *
 * <p>Matching is token-based (split on non-alphanumeric characters), not raw substring matching -
 * a whole-word tool name like {@code storedataset} or {@code widget} must never accidentally match
 * an unrelated shorter keyword ({@code store}, {@code get}) that merely happens to appear inside
 * it. Patterns are checked most-specific-first. An unmatched signature classifies as {@link
 * ToolOperationRole#UNKNOWN}, which is deliberately treated as non-bootstrap by {@link
 * ToolOperationRole#isBootstrap()} - a classification miss must never itself block a model from
 * finishing, only a confident bootstrap match can.</p>
 */
public final class ToolOperationClassifier {

    private static final Set<String> SELECTION_WORDS = Set.of("select", "choose", "activate");
    private static final Set<String> DISCOVERY_WORDS = Set.of(
            "list", "discover", "connections", "sessions", "instances", "status");
    private static final Set<String> SEARCH_WORDS = Set.of("search", "query", "find", "grep");
    private static final Set<String> INSPECT_WORDS = Set.of("inspect", "describe");
    private static final Set<String> VERIFY_WORDS = Set.of("verify", "validate", "check");
    private static final Set<String> EXECUTE_WORDS = Set.of(
            "execute", "run", "play", "perform", "navigate", "navigation", "input");
    private static final Set<String> WRITE_WORDS = Set.of(
            "write", "create", "update", "edit", "delete", "insert", "generate",
            "store", "submit", "append", "finalize", "upload", "notify");
    private static final Set<String> READ_WORDS = Set.of("read", "get");

    private ToolOperationClassifier() {
    }

    /**
     * Classifies a tool call.
     *
     * @param tool tool name
     * @param operation operation name
     * @return best-effort role classification
     */
    public static ToolOperationRole classify(String tool, String operation) {
        List<String> tokens = tokenize(safe(tool) + "_" + safe(operation));
        if (hasAdjacentPair(tokens, "set", "active") || hasAdjacentPair(tokens, "switch", "active")
                || containsAny(tokens, SELECTION_WORDS)) {
            return ToolOperationRole.SELECTION;
        }
        if (containsAny(tokens, DISCOVERY_WORDS) || hasAdjacentPair(tokens, "studio", "state")) {
            return ToolOperationRole.DISCOVERY;
        }
        if (containsAny(tokens, SEARCH_WORDS)) {
            return ToolOperationRole.SEARCH;
        }
        if (containsAny(tokens, INSPECT_WORDS)) {
            return ToolOperationRole.INSPECT;
        }
        if (containsAny(tokens, VERIFY_WORDS)) {
            return ToolOperationRole.VERIFY;
        }
        if (containsAny(tokens, EXECUTE_WORDS)) {
            return ToolOperationRole.EXECUTE;
        }
        if (containsAny(tokens, WRITE_WORDS)) {
            return ToolOperationRole.WRITE;
        }
        if (containsAny(tokens, READ_WORDS)) {
            return ToolOperationRole.READ;
        }
        return ToolOperationRole.UNKNOWN;
    }

    private static boolean containsAny(List<String> tokens, Set<String> words) {
        for (String token : tokens) {
            if (words.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAdjacentPair(List<String> tokens, String first, String second) {
        for (int index = 0; index < tokens.size() - 1; index++) {
            if (tokens.get(index).equals(first) && tokens.get(index + 1).equals(second)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> tokenize(String signature) {
        List<String> tokens = new ArrayList<>();
        for (String token : signature.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
