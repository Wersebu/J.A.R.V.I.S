package com.jarvis.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for mixed-case tool names: NativeToolSchemaMapper always lowercases the
 * function name shown to the model (e.g. "storeDataset" -> "storedataset__create_dataset"), so
 * the model echoes back the lowercased name on every tool call. If the manager looked up tools
 * by exact-case name, any tool whose getName() wasn't already all-lowercase (e.g. "storeDataset")
 * would be unreachable at runtime even though it registered successfully at startup.
 */
class DefaultToolManagerTest {

    private static final class MixedCaseTool implements JarvisTool {
        @Override
        public String getName() {
            return "storeDataset";
        }

        @Override
        public String getDescription() {
            return "test tool";
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            return new ToolResult(true, "", request.operation(), request.requestId(), request.conversationId(),
                    false, List.of(), "ok", Map.of(), "", "", false, "");
        }
    }

    @Test
    void executeResolvesAToolRegisteredWithMixedCaseNameWhenCalledWithItsLowercasedForm() {
        DefaultToolManager manager = new DefaultToolManager(List.of(new MixedCaseTool()));

        ToolResult result = manager.execute(new ToolRequest(
                "storedataset", "CREATE_DATASET", "conv-1", "req-1", "reason", "", Map.of()));

        assertTrue(result.success());
    }

    @Test
    void findToolResolvesAToolRegisteredWithMixedCaseNameWhenQueriedWithItsLowercasedForm() {
        DefaultToolManager manager = new DefaultToolManager(List.of(new MixedCaseTool()));

        assertTrue(manager.findTool("storedataset").isPresent());
        assertTrue(manager.findTool("STOREDATASET").isPresent());
        assertTrue(manager.findTool("storeDataset").isPresent());
    }

    @Test
    void executeStillFailsForATrulyUnregisteredTool() {
        DefaultToolManager manager = new DefaultToolManager(List.of(new MixedCaseTool()));

        ToolException exception = assertThrows(ToolException.class, () -> manager.execute(
                new ToolRequest("doesNotExist", "NOOP", "conv-1", "req-1", "reason", "", Map.of())));

        assertEquals("Tool not registered: doesNotExist", exception.getMessage());
    }
}
