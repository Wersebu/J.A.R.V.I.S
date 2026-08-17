package com.jarvis.tools.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ModelMessage;
import com.jarvis.common.ai.ModelResponse;
import com.jarvis.common.ai.ModelUsage;
import com.jarvis.common.ai.NativeToolDefinition;
import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.tools.ToolManager;
import com.jarvis.tools.schema.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test proving that store addresses the main model already extracted from a
 * current-message image (and put into the TOOL_REQUEST goal, per the current-message-attachments
 * prompt policy) actually reach the native tool loop's own model call.
 *
 * <p>Images themselves never travel into the tool loop (see {@code ModelMessage}, which has no
 * image-carrying variant) - but as long as the extracted text does, no information is lost, since
 * the model reads it back from {@code Tool goal:} in the loop's own system prompt instead of
 * needing to re-see the image.</p>
 */
class NativeToolLoopServiceAttachmentGoalPropagationTest {

    @Test
    void extractedAddressesInTheToolRequestGoalReachTheNativeLoopsSystemPrompt() {
        String extractedAddresses = "Korczaka 7, 08-400 Garwolin; Targowa 1, 08-400 Garwolin; Trakt Lwowski 41, 08-400 Garwolin";
        CapturingProvider provider = new CapturingProvider();

        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), new NoopToolManager(), query -> ToolIntent.LOCATION,
                new com.jarvis.tools.ToolRuntimeProperties(true, 4, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry())
        );

        service.execute(new ToolCallingRequest(
                "request-1", "conversation-1",
                "przygotuj grafik na sierpien",
                "Geocode the following extracted store addresses: " + extractedAddresses,
                "Need coordinates to build the visit schedule.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(provider.capturedSystemMessage()).contains(extractedAddresses);
        assertThat(provider.capturedSystemMessage()).contains("Tool goal:");
    }

    private static ToolRegistry registry() {
        com.jarvis.tools.schema.ToolDefinition location = new com.jarvis.tools.schema.ToolDefinition(
                "location", "Geocoding and routing.", List.of(
                new com.jarvis.tools.schema.ToolOperationDefinition("GEOCODE", "Geocode addresses.", List.of(
                        new com.jarvis.tools.schema.ToolArgumentDefinition("queries", "array", true, "Addresses")
                ), false, com.jarvis.tools.schema.ToolSafetyLevel.READ)
        ));
        return new ToolRegistry() {
            @Override
            public List<com.jarvis.tools.schema.ToolDefinition> definitions() {
                return List.of(location);
            }

            @Override
            public String promptSection() {
                return "";
            }
        };
    }

    private static final class CapturingProvider implements AIProvider {

        private String capturedSystemMessage = "";

        String capturedSystemMessage() {
            return capturedSystemMessage;
        }

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public ChatResponse chat(Brain brain, String prompt) {
            return new ChatResponse("");
        }

        @Override
        public void stream(String conversationId, Brain brain, String prompt, ChatEventSink eventSink) {
        }

        @Override
        public ModelResponse toolChat(Brain brain, List<ModelMessage> messages, List<NativeToolDefinition> tools, AIJobType jobType) {
            capturedSystemMessage = messages.stream()
                    .filter(message -> "system".equals(message.role()))
                    .map(ModelMessage::content)
                    .findFirst()
                    .orElse("");
            return new ModelResponse("Gotowe, oto wstepny plan.", "", List.of(), "stop", new ModelUsage(0, 0, 0));
        }
    }

    private static final class NoopToolManager implements ToolManager {

        @Override
        public List<com.jarvis.tools.JarvisTool> listTools() {
            return List.of();
        }

        @Override
        public java.util.Optional<com.jarvis.tools.JarvisTool> findTool(String name) {
            return java.util.Optional.empty();
        }

        @Override
        public com.jarvis.tools.ToolResult execute(com.jarvis.tools.ToolRequest request) {
            throw new UnsupportedOperationException("No tool call expected in this test");
        }
    }

    private static final class NoopCognitiveEventBus implements CognitiveEventBus {

        @Override
        public void startRequest(String requestId, String conversationId, java.util.function.Consumer<com.jarvis.common.event.CognitiveEvent> sink) {
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
