package com.jarvis.tools.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ImageAttachment;
import com.jarvis.common.ai.ModelMessage;
import com.jarvis.common.ai.ModelResponse;
import com.jarvis.common.ai.ModelToolCall;
import com.jarvis.common.ai.ModelUsage;
import com.jarvis.common.ai.NativeToolDefinition;
import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolManager;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.ToolRuntimeProperties;
import com.jarvis.tools.dataset.StoreAuditDatasetService;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolRegistry;
import com.jarvis.tools.schema.ToolSafetyLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the exact reported failure: a vision model correctly reads store data off
 * attached images, requests a tool, and - once the loop reaches the native tool-calling runtime -
 * forgets the images ever existed and asks the user to resend them. {@link ModelMessage}/{@link
 * ToolCallingRequest} now carry images by reference into the loop; these tests prove they actually
 * arrive at the provider, survive a tool call, and that a dataset from an earlier conversation turn
 * is surfaced without needing the attachments resent.
 */
class NativeToolLoopServiceMultimodalContinuityTest {

    // TEST 1: images survive the tool transition - present on the user turn sent to the provider
    // both before AND after a native tool call executes, not just on the very first call.
    @Test
    void imagesSurviveTheTransitionThroughANativeToolCall() {
        ImageAttachment photo = new ImageAttachment("base64data", "sklepy.jpg");
        RecordingProvider provider = new RecordingProvider();
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), new EchoToolManager(), query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 4, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(knowledgeRegistry()),
                new StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        service.execute(new ToolCallingRequest(
                "request-1", "conversation-1",
                "ustaw grafik na sierpien",
                "READ Work/Scheduling/StoreAuditScheduleWorkflow.md",
                "Need the workflow procedure before extracting store data.",
                "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST,
                List.of(photo)
        ));

        assertThat(provider.capturedMessageLists).hasSizeGreaterThanOrEqualTo(2);
        for (List<ModelMessage> captured : provider.capturedMessageLists) {
            ModelMessage userTurn = captured.stream().filter(message -> "user".equals(message.role())).findFirst().orElseThrow();
            assertThat(userTurn.images()).extracting(ImageAttachment::originalFileName).containsExactly("sklepy.jpg");
        }
    }

    // The model previously had no way to know a real current-message attachment id and fell back
    // to inventing one (e.g. "attachment_0"), which then always failed storeDataset's provenance
    // check. ImageAttachmentStage now threads the real AttachmentReference.attachmentId() through,
    // and the system prompt must list it so the model can cite it verbatim.
    @Test
    void systemPromptListsTheRealAttachmentIdOfEachImageWhenKnown() {
        ImageAttachment photo = new ImageAttachment("base64data", "sklepy.jpg", "real-attachment-42");
        RecordingProvider provider = new RecordingProvider();
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), new EchoToolManager(), query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 4, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(knowledgeRegistry()),
                new StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "ustaw grafik na sierpien",
                "extract records", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST,
                List.of(photo)
        ));

        ModelMessage systemTurn = provider.capturedMessageLists.get(0).stream()
                .filter(message -> "system".equals(message.role())).findFirst().orElseThrow();
        assertThat(systemTurn.content()).contains("CURRENT MESSAGE ATTACHMENTS");
        assertThat(systemTurn.content()).contains("real-attachment-42");
        assertThat(systemTurn.content()).contains("sklepy.jpg");
    }

    @Test
    void systemPromptOmitsTheAttachmentBlockWhenNoImageHasAKnownId() {
        ImageAttachment photoWithoutId = new ImageAttachment("base64data", "sklepy.jpg");
        RecordingProvider provider = new RecordingProvider();
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), new EchoToolManager(), query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 4, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(knowledgeRegistry()),
                new StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "ustaw grafik na sierpien",
                "extract records", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST,
                List.of(photoWithoutId)
        ));

        ModelMessage systemTurn = provider.capturedMessageLists.get(0).stream()
                .filter(message -> "system".equals(message.role())).findFirst().orElseThrow();
        assertThat(systemTurn.content()).doesNotContain("CURRENT MESSAGE ATTACHMENTS");
    }

    @Test
    void aTextOnlyRequestNeverAttachesImagesToTheUserTurn() {
        RecordingProvider provider = new RecordingProvider();
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), new EchoToolManager(), query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 4, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(knowledgeRegistry()),
                new StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        service.execute(new ToolCallingRequest(
                "request-1", "conversation-1", "sprawdz w wiedzy karte graficzna",
                "Read the hardware document.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        assertThat(provider.capturedMessageLists).isNotEmpty();
        ModelMessage userTurn = provider.capturedMessageLists.get(0).stream()
                .filter(message -> "user".equals(message.role())).findFirst().orElseThrow();
        assertThat(userTurn.images()).isEmpty();
    }

    // TEST 7: a dataset created for this conversation in an earlier turn is surfaced to the model
    // via the tool-loop system prompt, without the current request having any images/attachments at
    // all - proving turn 2 ("polacz dzien 3 i 4") never needs the original screenshots resent.
    @Test
    void anExistingConversationDatasetIsSurfacedInTheSystemPromptWithoutNewAttachments() {
        StoreAuditDatasetService datasetService = new StoreAuditDatasetService(new NoopCognitiveEventBus());
        datasetService.registerAttachments("earlier-request", "conversation-99", List.of("att-1"));
        var created = datasetService.createDataset("earlier-request", 1, List.of("att-1"), List.of(
                new com.jarvis.tools.dataset.CandidateRecord("Biedronka", "Miasto", "Ulica", "1", "00-001",
                        "Ulica 1, 00-001 Miasto", "att-1", 1)
        ));
        String datasetId = created.dataset().datasetId();

        RecordingProvider provider = new RecordingProvider();
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), new EchoToolManager(), query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 4, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(knowledgeRegistry()),
                datasetService
        );

        service.execute(new ToolCallingRequest(
                "request-2", "conversation-99", "polacz dzien 3 i 4",
                "Merge day 3 and 4 of the existing schedule.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        ModelMessage systemTurn = provider.capturedMessageLists.get(0).stream()
                .filter(message -> "system".equals(message.role())).findFirst().orElseThrow();
        assertThat(systemTurn.content()).contains(datasetId);
        assertThat(systemTurn.content()).contains("1 record");
    }

    @Test
    void noExistingDatasetForTheConversationLeavesTheSystemPromptUnchanged() {
        RecordingProvider provider = new RecordingProvider();
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(provider), new EchoToolManager(), query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 4, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(knowledgeRegistry()),
                new StoreAuditDatasetService(new NoopCognitiveEventBus())
        );

        service.execute(new ToolCallingRequest(
                "request-1", "conversation-brand-new", "sprawdz w wiedzy karte graficzna",
                "Read the hardware document.", "test", "Base prompt",
                new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        ));

        ModelMessage systemTurn = provider.capturedMessageLists.get(0).stream()
                .filter(message -> "system".equals(message.role())).findFirst().orElseThrow();
        assertThat(systemTurn.content()).doesNotContain("An existing dataset");
    }

    private static ToolRegistry knowledgeRegistry() {
        ToolDefinition knowledge = new ToolDefinition("knowledge", "Manages the Knowledge Workspace.", List.of(
                new ToolOperationDefinition("READ_DOCUMENT", "Read a document by logical path.", List.of(
                        new ToolArgumentDefinition("path", "string", true, "Logical path")
                ), false, ToolSafetyLevel.READ)
        ));
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of(knowledge);
            }

            @Override
            public String promptSection() {
                return "Tool: knowledge READ_DOCUMENT";
            }
        };
    }

    /**
     * Captures every {@code messages} list it is called with, then returns one tool call on its
     * first invocation and a plain final answer afterward - so a test can assert on the state of
     * the message list both before and after a native tool call executes.
     */
    private static final class RecordingProvider implements AIProvider {

        private final List<List<ModelMessage>> capturedMessageLists = new ArrayList<>();
        private int calls;

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
            capturedMessageLists.add(List.copyOf(messages));
            calls++;
            if (calls == 1) {
                return new ModelResponse("", "", List.of(new ModelToolCall("call-1", "knowledge__read_document",
                        Map.of("path", "Work/Scheduling/StoreAuditScheduleWorkflow.md"))), "tool_calls", new ModelUsage(0, 0, 0));
            }
            return new ModelResponse("Final answer text.", "", List.of(), "stop", new ModelUsage(0, 0, 0));
        }
    }

    private static final class EchoToolManager implements ToolManager {

        @Override
        public List<JarvisTool> listTools() {
            return List.of();
        }

        @Override
        public Optional<JarvisTool> findTool(String name) {
            return Optional.of(new JarvisTool() {
                @Override
                public String getName() {
                    return name;
                }

                @Override
                public String getDescription() {
                    return "stub";
                }

                @Override
                public ToolResult execute(ToolRequest request) {
                    return new ToolResult(true, request.toolName(), request.operation(), request.requestId(),
                            request.conversationId(), false, List.of(), "Document read",
                            Map.of("content", "Workflow procedure text."), "", "", false, "");
                }
            });
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            return findTool(request.toolName()).orElseThrow().execute(request);
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
