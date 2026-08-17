package com.jarvis.tools.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolManager;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.ToolRuntimeProperties;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the tool-result compaction bug: {@code compactData()} used to be a
 * hardcoded allowlist of field names tuned for web-search results. Any other tool's structural
 * fields — like LIST_TREE/LIST_FOLDER's "entries" or a plain "path" — were silently dropped
 * before the result ever reached the model, so the model never saw the document path it needed
 * for READ_DOCUMENT.
 */
class NativeToolLoopServiceCompactionTest {

    @Test
    void compactDataPreservesStructuralFieldsForAnyTool() throws Exception {
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(), new NoopToolManager(), query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 2, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(emptyRegistry()),
                new com.jarvis.tools.dataset.StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        Map<String, Object> data = Map.of(
                "entries", List.of(Map.of("type", "file", "path", "hardware/graphics_card.txt", "name", "graphics_card.txt")),
                "path", "hardware"
        );

        Method compactData = NativeToolLoopService.class.getDeclaredMethod("compactData", Map.class);
        compactData.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> compacted = (Map<String, Object>) compactData.invoke(service, data);

        assertThat(compacted).containsKey("entries");
        assertThat(compacted).containsKey("path");
        assertThat(compacted.get("path")).isEqualTo("hardware");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) compacted.get("entries");
        assertThat(entries).anySatisfy(entry -> assertThat(entry.get("path")).isEqualTo("hardware/graphics_card.txt"));
    }

    @Test
    void compactDataBoundsLargeContentAndListsWithoutDroppingThem() throws Exception {
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(), new NoopToolManager(), query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 2, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(emptyRegistry()),
                new com.jarvis.tools.dataset.StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        String longContent = "x".repeat(5000);
        List<Map<String, Object>> manyResults = java.util.stream.IntStream.range(0, 50)
                .mapToObj(index -> Map.<String, Object>of("url", "https://example.com/" + index))
                .toList();
        Map<String, Object> data = Map.of("content", longContent, "results", manyResults);

        Method compactData = NativeToolLoopService.class.getDeclaredMethod("compactData", Map.class);
        compactData.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> compacted = (Map<String, Object>) compactData.invoke(service, data);

        assertThat(((String) compacted.get("content")).length()).isLessThan(longContent.length());
        assertThat((List<?>) compacted.get("results")).hasSizeLessThan(manyResults.size());
    }

    private static ToolRegistry emptyRegistry() {
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of();
            }

            @Override
            public String promptSection() {
                return "";
            }
        };
    }

    private static final class NoopToolManager implements ToolManager {

        @Override
        public List<JarvisTool> listTools() {
            return List.of();
        }

        @Override
        public Optional<JarvisTool> findTool(String name) {
            return Optional.empty();
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            throw new AssertionError("Tool execution should not be reached");
        }
    }

    private static final class NoopCognitiveEventBus implements CognitiveEventBus {

        @Override
        public void startRequest(String requestId, String conversationId, Consumer<CognitiveEvent> sink) {
        }

        @Override
        public void finishRequest() {
        }

        @Override
        public void updateBrain(BrainType brain, String model) {
        }

        @Override
        public void publish(CognitiveEventType event, String status, String message, String nodeId, Map<String, Object> metadata) {
        }
    }
}
