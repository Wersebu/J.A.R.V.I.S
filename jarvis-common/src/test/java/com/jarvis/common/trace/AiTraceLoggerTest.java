package com.jarvis.common.trace;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AiTraceLogger}'s pure formatting - the actual gating/log-emission methods
 * are exercised end-to-end from {@code NativeToolLoopService}/{@code OllamaProvider}/{@code
 * DefaultMcpServerManager} tests; these pin down the exact block structure and the two safety
 * behaviors (secret redaction, binary omission) independent of any caller.
 */
class AiTraceLoggerTest {

    @Test
    void formatOutboundAiRequestIncludesAllRequiredFieldsAndPayloadByteCount() {
        String requestJson = "{\"model\":\"gemma4:26b\",\"messages\":[]}";
        String block = AiTraceLogger.formatOutboundAiRequest(
                "req-1", "gemma4:26b", "http://localhost:11434/api/chat", "MAIN_MODEL", "LOW", 2, requestJson);

        assertThat(block).startsWith("================ AI REQUEST BEGIN ================");
        assertThat(block).endsWith("================ AI REQUEST END ==================");
        assertThat(block).contains("requestId=req-1");
        assertThat(block).contains("model=gemma4:26b");
        assertThat(block).contains("endpoint=http://localhost:11434/api/chat");
        assertThat(block).contains("jobType=MAIN_MODEL");
        assertThat(block).contains("reasoningLevel=LOW");
        assertThat(block).contains("turn=2");
        assertThat(block).contains("payloadBytes=" + requestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertThat(block).contains("\"model\" : \"gemma4:26b\"");
    }

    @Test
    void formatModelToolCallShowsToolAndIndexAndPrettyPrintsArguments() {
        String block = AiTraceLogger.formatModelToolCall("req-1", 1, 0, "mcp_roblox_list_roblox_studios",
                Map.of("query", "*"));

        assertThat(block).startsWith("================ MODEL TOOL CALL =================");
        assertThat(block).contains("requestId=req-1");
        assertThat(block).contains("turn=1");
        assertThat(block).contains("index=0");
        assertThat(block).contains("tool=mcp_roblox_list_roblox_studios");
        assertThat(block).contains("\"query\" : \"*\"");
    }

    @Test
    void formatToolExecutionBeginShowsSourceAndMcpServer() {
        String block = AiTraceLogger.formatToolExecutionBegin("req-1", "conv-1", "mcp_roblox_list_roblox_studios",
                "CALL", "MCP", "roblox", Map.of());

        assertThat(block).contains("source=MCP");
        assertThat(block).contains("mcpServer=roblox");
        assertThat(block).contains("tool=mcp_roblox_list_roblox_studios");
        assertThat(block).contains("operation=CALL");
    }

    @Test
    void formatToolExecutionBeginShowsNativeSourceWithNoMcpServer() {
        String block = AiTraceLogger.formatToolExecutionBegin("req-1", "conv-1", "storedataset", "GET_DATASET",
                "NATIVE", "", Map.of());

        assertThat(block).contains("source=NATIVE");
        assertThat(block).contains("mcpServer=n/a");
    }

    @Test
    void formatMcpCallBeginShowsBothModelFacingAndRealToolNames() {
        String block = AiTraceLogger.formatMcpCallBegin("req-1", "roblox",
                "mcp_roblox_list_roblox_studios", "list_roblox_studios", Map.of());

        assertThat(block).contains("server=roblox");
        assertThat(block).contains("modelFacingTool=mcp_roblox_list_roblox_studios");
        assertThat(block).contains("mcpTool=list_roblox_studios");
    }

    @Test
    void formatToolResultShowsSuccessAndErrorFields() {
        String failure = AiTraceLogger.formatToolResult("req-1", "web", false, false,
                "TOOL_EXECUTION_FAILED", "timed out", Map.of());
        assertThat(failure).contains("success=false");
        assertThat(failure).contains("errorCode=TOOL_EXECUTION_FAILED");
        assertThat(failure).contains("errorMessage=timed out");

        String success = AiTraceLogger.formatToolResult("req-1", "web", true, true, "", "", Map.of("count", 3));
        assertThat(success).contains("success=true");
        assertThat(success).contains("changed=true");
        assertThat(success).contains("\"count\" : 3");
    }

    @Test
    void secretShapedKeysAreRedactedButOrdinaryFieldsAreNot() {
        String block = AiTraceLogger.prettyPrintSafe(Map.of(
                "Authorization", "Bearer super-secret-token",
                "apiKey", "sk-12345",
                "password", "hunter2",
                "cookie", "session=abc",
                "userMessage", "list the folders"
        ));

        assertThat(block).contains("<redacted>");
        assertThat(block).doesNotContain("super-secret-token");
        assertThat(block).doesNotContain("sk-12345");
        assertThat(block).doesNotContain("hunter2");
        assertThat(block).doesNotContain("session=abc");
        assertThat(block).contains("list the folders");
    }

    @Test
    void longBase64LikeStringsAreOmittedNotDumped() {
        String base64Image = "iVBOR" + "A".repeat(500);
        String block = AiTraceLogger.prettyPrintSafe(Map.of("image", base64Image));

        assertThat(block).doesNotContain(base64Image);
        assertThat(block).contains("<binary omitted");
        assertThat(block).contains("mimeType=image/png");
    }

    @Test
    void ordinaryTextAndJsonPrintInFullEvenWhenLong() {
        String longButNotBinary = "This is a perfectly ordinary sentence. ".repeat(20);
        String block = AiTraceLogger.prettyPrintSafe(Map.of("content", longButNotBinary));

        assertThat(block).contains(longButNotBinary.strip());
    }

    @Test
    void shortBase64LikeStringsAreNeverTreatedAsBinary() {
        // Below the minimum length threshold - a short id/token-looking string must never be
        // mistaken for binary data and hidden from the log.
        assertThat(AiTraceLogger.looksBinary("dGhpcyBpcyBzaG9ydA==")).isFalse();
    }

    @Test
    void plainTextThatIsNotValidJsonStillPrintsInFullRatherThanFailing() {
        String block = AiTraceLogger.prettyPrintSafe("Nie znaleziono zadnych plikow w projekcie.");
        assertThat(block).isEqualTo("Nie znaleziono zadnych plikow w projekcie.");
    }

    @Test
    void emptyOrNullValuesFormatAsEmptyRatherThanThrowing() {
        assertThat(AiTraceLogger.prettyPrintSafe((String) null)).isEqualTo("(empty)");
        assertThat(AiTraceLogger.prettyPrintSafe((Object) null)).isEqualTo("(empty)");
        assertThat(AiTraceLogger.prettyPrintSafe("")).isEqualTo("(empty)");
    }

    @Test
    void listsAndNestedStructuresPrettyPrintCorrectly() {
        String block = AiTraceLogger.prettyPrintSafe(Map.of("folders", List.of("Workspace", "ReplicatedStorage")));
        assertThat(block).contains("Workspace");
        assertThat(block).contains("ReplicatedStorage");
    }

    @Test
    void isSecretKeyMatchesCommonCredentialShapedNamesCaseInsensitively() {
        assertThat(AiTraceLogger.isSecretKey("Authorization")).isTrue();
        assertThat(AiTraceLogger.isSecretKey("api_key")).isTrue();
        assertThat(AiTraceLogger.isSecretKey("apiKey")).isTrue();
        assertThat(AiTraceLogger.isSecretKey("PASSWORD")).isTrue();
        assertThat(AiTraceLogger.isSecretKey("access_token")).isTrue();
        assertThat(AiTraceLogger.isSecretKey("token")).isTrue();
        assertThat(AiTraceLogger.isSecretKey("userMessage")).isFalse();
        assertThat(AiTraceLogger.isSecretKey("tool")).isFalse();
    }
}
