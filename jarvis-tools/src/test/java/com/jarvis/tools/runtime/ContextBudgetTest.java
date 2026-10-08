package com.jarvis.tools.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.ModelMessage;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBudgetTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolOutputStore store = new ToolOutputStore();

    @Test
    void smallResultsAreSentCompletely() {
        String content = "x".repeat(9_000);
        String json = ContextBudget.fit(Map.of("data", Map.of("content", content)), 16_000, store, objectMapper);
        assertThat(json).contains(content).doesNotContain("_shortened");
    }

    @Test
    void largeResultsAreShortenedVisiblyAndFullyReadableThroughOutputTool() {
        String log = IntStream.rangeClosed(1, 5_000).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));
        String json = ContextBudget.fit(Map.of("tool", "pc", "data", Map.of("output", log)), 4_000, store, objectMapper);

        assertThat(json.length()).isLessThanOrEqualTo(4_000);
        assertThat(json).contains("line 1\\n").contains("line 5000").contains("characters omitted").contains("_shortened");
        String outputId = json.replaceAll(".*\"outputId\":\"(out-[^\"]+)\".*", "$1");

        OutputTool tool = new OutputTool(store);
        ToolResult middle = tool.execute(new ToolRequest("output", "READ", "c", "r", "", "",
                Map.of("outputId", outputId, "startLine", 2_500, "limit", 3)));
        assertThat(middle.data().get("content").toString()).contains("line 2499").contains("line 2500");

        ToolResult grep = tool.execute(new ToolRequest("output", "GREP", "c", "r", "", "",
                Map.of("outputId", outputId, "pattern", "^line 4242$")));
        assertThat(grep.data().get("matches")).isEqualTo(1);
    }

    @Test
    void longListsKeepAMarker() {
        List<Map<String, Object>> files = IntStream.range(0, 2_000)
                .mapToObj(i -> Map.<String, Object>of("path", "C:\\\\dir\\\\file" + i + ".txt")).toList();
        String json = ContextBudget.fit(Map.of("data", Map.of("files", files)), 5_000, store, objectMapper);
        assertThat(json).contains("more items omitted").contains("output__read");
    }

    @Test
    void historyCompactionElidesOldestToolResultsButKeepsRecentOnes() {
        List<ModelMessage> messages = new ArrayList<>();
        messages.add(ModelMessage.system("system prompt"));
        for (int i = 0; i < 10; i++) {
            messages.add(ModelMessage.tool("call-" + i, "pc__read", "{\"n\":" + i + ",\"content\":\"" + "y".repeat(10_000) + "\"}"));
        }
        int elided = ContextBudget.compactHistory(messages, 50_000, 4, store);

        assertThat(elided).isGreaterThanOrEqualTo(5);
        assertThat(messages.get(1).content()).startsWith("{\"elided\":true").contains("outputId");
        assertThat(messages.get(10).content()).contains("y".repeat(10_000));
        assertThat(messages.get(1).toolCallId()).isEqualTo("call-0");
        assertThat(messages.stream().mapToInt(message -> message.content().length()).sum()).isLessThanOrEqualTo(50_000);
    }
}
