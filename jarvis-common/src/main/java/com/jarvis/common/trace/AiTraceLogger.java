package com.jarvis.common.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Full diagnostic AI/tool trace: DEBUG-only, human-readable logging of the exact payloads crossing
 * the USER -> MODEL -> TOOL_CALL -> MCP -> TOOL_RESULT -> MODEL boundary, gated by {@link
 * AiTraceSettings} so the cost is a single volatile-field read when disabled (default in
 * production).
 *
 * <p><b>This is a debugging feature.</b> When enabled it can log an entire conversation, system
 * prompt, tool arguments, and tool results verbatim. See the README "Diagnostics" section before
 * enabling it anywhere logs are shipped off-box. Known secret-shaped keys (see {@link
 * #isSecretKey(String)}) are redacted before logging; this is a best-effort keyword match, not a
 * substitute for keeping trace logs out of untrusted hands.</p>
 */
public final class AiTraceLogger {

    private static final Logger LOGGER = LoggerFactory.getLogger("AI_TRACE");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Strings shorter than this are never treated as binary/base64, however they look. */
    private static final int MIN_BINARY_LENGTH = 300;
    private static final Pattern BASE64_LIKE = Pattern.compile("^[A-Za-z0-9+/_=-]+$");
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i)authoriz|api[-_]?key|password|passwd|cookie|secret|(^|[-_])token($|[-_])|access[-_]?token");

    private AiTraceLogger() {
    }

    // ------------------------------------------------------------------
    // 1/2/3 - Outbound AI request, logged from the exact same String used for the HTTP body.
    // ------------------------------------------------------------------

    /**
     * Logs the outbound AI request - callers MUST pass the exact same {@code String} they hand to
     * the HTTP body publisher, never a re-serialized copy, so the log is guaranteed to match what
     * was actually sent.
     *
     * @param requestId pipeline/diagnostics request id (stable across every turn of one request)
     * @param model target model name
     * @param endpoint Ollama endpoint URL
     * @param jobType AI job type (e.g. MAIN_MODEL)
     * @param reasoningLevel effective reasoning level
     * @param turn 1-based tool-loop turn number, {@code 0} for a non-tool-loop call
     * @param rawRequestJson the exact JSON string sent as the HTTP request body
     */
    public static void logOutboundAiRequest(
            String requestId, String model, String endpoint, String jobType, String reasoningLevel,
            int turn, String rawRequestJson
    ) {
        if (!AiTraceSettings.logFullAiRequest()) {
            return;
        }
        LOGGER.info(formatOutboundAiRequest(requestId, model, endpoint, jobType, reasoningLevel, turn, rawRequestJson));
    }

    /**
     * Pure formatting for {@link #logOutboundAiRequest}, directly unit-testable.
     *
     * @param requestId pipeline/diagnostics request id
     * @param model target model name
     * @param endpoint Ollama endpoint URL
     * @param jobType AI job type
     * @param reasoningLevel effective reasoning level
     * @param turn 1-based tool-loop turn number, {@code 0} for a non-tool-loop call
     * @param rawRequestJson the exact JSON string sent as the HTTP request body
     * @return formatted trace block
     */
    public static String formatOutboundAiRequest(
            String requestId, String model, String endpoint, String jobType, String reasoningLevel,
            int turn, String rawRequestJson
    ) {
        int payloadBytes = rawRequestJson == null ? 0 : rawRequestJson.getBytes(StandardCharsets.UTF_8).length;
        return """
                ================ AI REQUEST BEGIN ================
                requestId=%s
                model=%s
                endpoint=%s
                jobType=%s
                reasoningLevel=%s
                turn=%s
                payloadBytes=%s

                %s

                ================ AI REQUEST END ==================""".formatted(
                requestId, model, endpoint, jobType, reasoningLevel, turn, payloadBytes, prettyPrintSafe(rawRequestJson));
    }

    // ------------------------------------------------------------------
    // 4 - Model tool call output.
    // ------------------------------------------------------------------

    /**
     * Logs one model-generated tool call.
     *
     * @param requestId pipeline/diagnostics request id
     * @param turn 1-based tool-loop turn number
     * @param index 0-based index within this turn's tool calls (a turn may contain several)
     * @param tool model-facing tool/function name
     * @param arguments tool call arguments (a {@code Map}, or any Jackson-serializable value)
     */
    public static void logModelToolCall(String requestId, int turn, int index, String tool, Object arguments) {
        if (!AiTraceSettings.logToolCalls()) {
            return;
        }
        LOGGER.info(formatModelToolCall(requestId, turn, index, tool, arguments));
    }

    /**
     * Pure formatting for {@link #logModelToolCall}.
     *
     * @param requestId pipeline/diagnostics request id
     * @param turn 1-based tool-loop turn number
     * @param index 0-based index within this turn's tool calls
     * @param tool model-facing tool/function name
     * @param argumentsJson tool call arguments
     * @return formatted trace block
     */
    public static String formatModelToolCall(String requestId, int turn, int index, String tool, Object argumentsJson) {
        return """
                ================ MODEL TOOL CALL =================
                requestId=%s
                turn=%s
                index=%s
                tool=%s

                arguments:
                %s
                ==================================================""".formatted(
                requestId, turn, index, tool, prettyPrintSafe(argumentsJson));
    }

    // ------------------------------------------------------------------
    // 5 - Tool execution begin.
    // ------------------------------------------------------------------

    /**
     * Logs a tool execution about to start.
     *
     * @param requestId pipeline/diagnostics request id
     * @param conversationId owning conversation id
     * @param tool model-facing tool name
     * @param operation operation name
     * @param source {@code "NATIVE"} or {@code "MCP"}
     * @param mcpServer MCP server id, blank when {@code source} is not {@code "MCP"}
     * @param arguments tool arguments (a {@code Map}, or any Jackson-serializable value)
     */
    public static void logToolExecutionBegin(
            String requestId, String conversationId, String tool, String operation,
            String source, String mcpServer, Object arguments
    ) {
        if (!AiTraceSettings.logToolCalls()) {
            return;
        }
        LOGGER.info(formatToolExecutionBegin(requestId, conversationId, tool, operation, source, mcpServer, arguments));
    }

    /**
     * Pure formatting for {@link #logToolExecutionBegin}.
     *
     * @param requestId pipeline/diagnostics request id
     * @param conversationId owning conversation id
     * @param tool model-facing tool name
     * @param operation operation name
     * @param source {@code "NATIVE"} or {@code "MCP"}
     * @param mcpServer MCP server id, blank when {@code source} is not {@code "MCP"}
     * @param argumentsJson tool arguments
     * @return formatted trace block
     */
    public static String formatToolExecutionBegin(
            String requestId, String conversationId, String tool, String operation,
            String source, String mcpServer, Object argumentsJson
    ) {
        return """
                ================ TOOL EXECUTION BEGIN ============
                requestId=%s
                conversationId=%s
                tool=%s
                operation=%s
                source=%s
                mcpServer=%s

                arguments:
                %s
                ==================================================""".formatted(
                requestId, conversationId, tool, operation, source,
                mcpServer == null || mcpServer.isBlank() ? "n/a" : mcpServer, prettyPrintSafe(argumentsJson));
    }

    // ------------------------------------------------------------------
    // 6 - MCP call boundary: model-facing name vs real MCP tool name.
    // ------------------------------------------------------------------

    /**
     * Logs a call crossing the MCP boundary - deliberately shows both the model-facing tool name
     * ({@code mcp_<server>_<tool>}) and the real MCP tool name sent to the MCP server, since a
     * mismatch between the two is a plausible root cause for a tool silently doing the wrong thing.
     *
     * @param requestId pipeline/diagnostics request id
     * @param server MCP server id
     * @param modelFacingToolName the {@code mcp_<server>_<tool>} name the model called
     * @param realMcpToolName the real tool name sent to the MCP server
     * @param arguments tool arguments (a {@code Map}, or any Jackson-serializable value)
     */
    public static void logMcpCallBegin(
            String requestId, String server, String modelFacingToolName, String realMcpToolName, Object arguments
    ) {
        if (!AiTraceSettings.logToolCalls()) {
            return;
        }
        LOGGER.info(formatMcpCallBegin(requestId, server, modelFacingToolName, realMcpToolName, arguments));
    }

    /**
     * Pure formatting for {@link #logMcpCallBegin}.
     *
     * @param requestId pipeline/diagnostics request id
     * @param server MCP server id
     * @param modelFacingToolName the {@code mcp_<server>_<tool>} name the model called
     * @param realMcpToolName the real tool name sent to the MCP server
     * @param argumentsJson tool arguments
     * @return formatted trace block
     */
    public static String formatMcpCallBegin(
            String requestId, String server, String modelFacingToolName, String realMcpToolName, Object argumentsJson
    ) {
        return """
                ================ MCP CALL BEGIN ==================
                requestId=%s
                server=%s
                modelFacingTool=%s
                mcpTool=%s

                arguments:
                %s
                ==================================================""".formatted(
                requestId, server, modelFacingToolName, realMcpToolName, prettyPrintSafe(argumentsJson));
    }

    // ------------------------------------------------------------------
    // 7 - Tool result.
    // ------------------------------------------------------------------

    /**
     * Logs a finished tool execution's result.
     *
     * @param requestId pipeline/diagnostics request id
     * @param tool model-facing tool name
     * @param success whether the call succeeded
     * @param changed whether the call changed state
     * @param errorCode error code, blank on success
     * @param errorMessage error message, blank on success
     * @param data result data/content (a {@code Map}, or any Jackson-serializable value)
     */
    public static void logToolResult(
            String requestId, String tool, boolean success, boolean changed,
            String errorCode, String errorMessage, Object data
    ) {
        if (!AiTraceSettings.logToolResults()) {
            return;
        }
        LOGGER.info(formatToolResult(requestId, tool, success, changed, errorCode, errorMessage, data));
    }

    /**
     * Pure formatting for {@link #logToolResult}.
     *
     * @param requestId pipeline/diagnostics request id
     * @param tool model-facing tool name
     * @param success whether the call succeeded
     * @param changed whether the call changed state
     * @param errorCode error code, blank on success
     * @param errorMessage error message, blank on success
     * @param dataJson result data/content
     * @return formatted trace block
     */
    public static String formatToolResult(
            String requestId, String tool, boolean success, boolean changed,
            String errorCode, String errorMessage, Object dataJson
    ) {
        return """
                ================ TOOL RESULT =====================
                requestId=%s
                tool=%s
                success=%s
                changed=%s
                errorCode=%s
                errorMessage=%s

                data/content:
                %s
                ==================================================""".formatted(
                requestId, tool, success, changed, errorCode, errorMessage, prettyPrintSafe(dataJson));
    }

    // ------------------------------------------------------------------
    // Native tool argument normalization (optional blank-string omission).
    // ------------------------------------------------------------------

    /**
     * Logs which optional, blank-string arguments a model tool call was normalized to omit before
     * validation/execution - a single compact line, not a full pretty-printed block, since there is
     * no large payload here worth the redaction/binary-omission machinery.
     *
     * @param tool model-facing function name (e.g. {@code mcp_roblox_search_game_tree__call})
     * @param omittedOptionalBlankArguments names of optional string arguments stripped for being blank
     */
    public static void logNativeToolArgumentNormalization(String tool, List<String> omittedOptionalBlankArguments) {
        if (!AiTraceSettings.logToolCalls() || omittedOptionalBlankArguments == null || omittedOptionalBlankArguments.isEmpty()) {
            return;
        }
        LOGGER.info("[NATIVE_TOOL_NORMALIZATION] tool={} omittedOptionalBlankArguments={}", tool, omittedOptionalBlankArguments);
    }

    // ------------------------------------------------------------------
    // Pretty-printing, redaction, binary omission.
    // ------------------------------------------------------------------

    /**
     * Pretty-prints a JSON string for logging, redacting secret-shaped keys and omitting
     * binary/base64-looking values - never used for the actual HTTP payload, which always uses the
     * original compact string. Falls back to the raw text (bounded) if it is not valid JSON, so a
     * plain-text tool result still prints in full.
     *
     * @param json raw JSON (or plain text) to format
     * @return pretty-printed, redacted text safe for logging
     */
    public static String prettyPrintSafe(String json) {
        if (json == null || json.isBlank()) {
            return "(empty)";
        }
        try {
            return prettyPrintTree(MAPPER.readTree(json));
        } catch (Exception exception) {
            // Not valid JSON (e.g. a plain-text tool result) - log it verbatim rather than fail.
            return json;
        }
    }

    /**
     * Pretty-prints an arbitrary Java value (typically a {@code Map} of tool call arguments or
     * result data) for logging, with the same redaction and binary-omission as {@link
     * #prettyPrintSafe(String)} - callers never need their own {@code ObjectMapper} just to log
     * tool arguments.
     *
     * @param value value to format; a {@code String} is treated as already-serialized JSON (or
     *         plain text) and delegates to {@link #prettyPrintSafe(String)}
     * @return pretty-printed, redacted text safe for logging
     */
    public static String prettyPrintSafe(Object value) {
        if (value instanceof String text) {
            return prettyPrintSafe(text);
        }
        if (value == null) {
            return "(empty)";
        }
        try {
            return prettyPrintTree(MAPPER.valueToTree(value));
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }

    private static String prettyPrintTree(JsonNode tree) throws com.fasterxml.jackson.core.JsonProcessingException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(redact(tree));
    }

    private static JsonNode redact(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = MAPPER.createObjectNode();
            node.fields().forEachRemaining(entry -> result.set(
                    entry.getKey(),
                    isSecretKey(entry.getKey()) ? TextNode.valueOf("<redacted>") : redact(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = MAPPER.createArrayNode();
            node.forEach(child -> result.add(redact(child)));
            return result;
        }
        if (node.isTextual()) {
            return redactBinaryText(node.asText());
        }
        return node;
    }

    private static JsonNode redactBinaryText(String text) {
        if (looksBinary(text)) {
            return TextNode.valueOf("<binary omitted mimeType=" + guessMimeType(text) + " bytes=" + estimateDecodedBytes(text) + ">");
        }
        return TextNode.valueOf(text);
    }

    /**
     * Whether {@code key} looks like it carries a credential/secret - a best-effort keyword match
     * (see class javadoc); redaction here is a safety net for diagnostic logging, not a security
     * boundary.
     *
     * @param key JSON field name
     * @return true when the key should be redacted
     */
    static boolean isSecretKey(String key) {
        return key != null && SECRET_KEY.matcher(key).find();
    }

    /**
     * Whether {@code text} looks like base64/binary payload data rather than ordinary text - long
     * enough, and drawn entirely from the base64 alphabet, or an explicit {@code data:} URI.
     *
     * @param text candidate string
     * @return true when it looks like binary data
     */
    static boolean looksBinary(String text) {
        if (text == null || text.length() < MIN_BINARY_LENGTH) {
            return false;
        }
        if (text.startsWith("data:")) {
            return true;
        }
        return BASE64_LIKE.matcher(text).matches();
    }

    private static String guessMimeType(String text) {
        if (text.startsWith("data:")) {
            int semicolon = text.indexOf(';');
            int colon = text.indexOf(':');
            if (semicolon > colon && colon >= 0) {
                return text.substring(colon + 1, semicolon);
            }
        }
        if (text.startsWith("iVBOR")) {
            return "image/png";
        }
        if (text.startsWith("/9j/")) {
            return "image/jpeg";
        }
        return "application/octet-stream";
    }

    private static long estimateDecodedBytes(String text) {
        // Base64 encodes 3 bytes as 4 characters - a rough estimate is all this is for, purely
        // informational in the log line.
        return Math.round(text.length() * 3.0 / 4.0);
    }
}
