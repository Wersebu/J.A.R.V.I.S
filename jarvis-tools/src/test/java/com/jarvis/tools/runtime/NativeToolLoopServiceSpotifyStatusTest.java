package com.jarvis.tools.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ModelMessage;
import com.jarvis.common.ai.ModelResponse;
import com.jarvis.common.ai.ModelToolCall;
import com.jarvis.common.ai.ModelUsage;
import com.jarvis.common.ai.NativeToolDefinition;
import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolManager;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.ToolRuntimeProperties;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolRegistry;
import com.jarvis.tools.schema.ToolSafetyLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Controlled reproduction of the completion mechanism; no production Ubuntu logs were inspected. */
class NativeToolLoopServiceSpotifyStatusTest {

    private static final String USER = "Sprawdź połączenie Spotify i pokaż dostępne urządzenia. Nie uruchamiaj muzyki.";
    private static final String GOAL = "Check the Spotify connection and show the available devices without playing music.";

    @Test
    void plainStatusAnswerIsAccepted() {
        ToolCallingResult result = run(statusConnected(), devices(List.of(device("DESKTOP-JARVIS", "Computer", false))),
                answer("Spotify jest połączone. Dostępne urządzenie: DESKTOP-JARVIS (komputer, nieaktywne). Niczego nie odtwarzam."));

        assertThat(result.terminationInfo().terminationReason()).isEqualTo(ToolLoopTerminationReason.COMPLETED);
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.terminationInfo().goalSatisfied()).isTrue();
        assertThat(result.finalAnswer()).contains("DESKTOP-JARVIS");
    }

    @Test
    void offeringAFollowUpActionIsNotARetryPermissionQuestion() {
        String reply = "Spotify jest połączone. Dostępne urządzenie: DESKTOP-JARVIS (komputer). Czy mam coś na nim włączyć?";
        ToolCallingResult result = run(statusConnected(), devices(List.of(device("DESKTOP-JARVIS", "Computer", false))),
                answer(reply), answer(reply));

        assertThat(result.terminationInfo().terminationReason()).isEqualTo(ToolLoopTerminationReason.COMPLETED);
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.terminationInfo().goalSatisfied()).isTrue();
        assertThat(result.finalAnswer()).isEqualTo(reply);
    }

    @Test
    void englishThinkingAboutNotTryingToPlayIsNotARetryPermissionQuestion() {
        String reply = "Spotify jest połączone, ale nie widzę żadnych urządzeń - otwórz aplikację Spotify na komputerze.";
        ModelResponse withThinking = new ModelResponse(reply,
                "The user asked not to play music, so I should not try again to start playback; just report the devices.",
                List.of(), "stop", new ModelUsage(0, 0, 0));
        ToolCallingResult result = run(statusConnected(), devices(List.of()), withThinking, withThinking);

        assertThat(result.terminationInfo().terminationReason()).isEqualTo(ToolLoopTerminationReason.COMPLETED);
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.terminationInfo().goalSatisfied()).isTrue();
        assertThat(result.finalAnswer()).contains("nie widzę żadnych urządzeń");
    }

    @Test
    void shortAnswerWithReadEvidenceCompletes() {
        ToolCallingResult result = run(statusConnected(), devices(List.of()), answer("Połączono. Urządzenia: 0."));
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.terminationInfo().successfulToolCalls()).isEqualTo(2);
        assertThat(result.finalAnswer()).isEqualTo("Połączono. Urządzenia: 0.");
    }

    @Test
    void disconnectedStatusAndEmptyDevicesAreValidReadResults() {
        ToolResult status = new ToolResult(true, "pc", "SPOTIFY", "", "", false, List.of(), "status read",
                Map.of("connected", false, "configured", true, "authorizationPending", false), "", "", false, "");
        ToolCallingResult result = run(status, devices(List.of()), answer("Spotify nie jest połączone. Dostępne urządzenia: 0."));
        assertThat(result.terminationInfo().completed()).isTrue();
        assertThat(result.terminationInfo().changesMade()).isFalse();
        assertThat(result.finalAnswer()).contains("nie jest połączone");
    }

    @Test
    void failedReadsAndRetryQuestionCannotComplete() {
        String retry = "Odczyt się nie udał. Czy mam spróbować ponownie?";
        ToolCallingResult result = run(failedRead(), failedRead(), answer(retry), answer(retry));
        assertThat(result.terminationInfo().completed()).isFalse();
        assertThat(result.terminationInfo().goalSatisfied()).isFalse();
        assertThat(result.finalAnswer()).contains("Nie mogę rzetelnie zakończyć").doesNotContain("Czy mam");
    }

    @Test
    void unsupportedSuccessDraftIsNeverReturnedAfterMissingEvidenceBlock() {
        String claim = "Spotify jest połączone. Urządzenie DESKTOP działa poprawnie.";
        ToolCallingResult result = run(failedRead(), failedRead(), answer(claim), answer(claim));
        assertThat(result.terminationInfo().completed()).isFalse();
        assertThat(result.terminationInfo().goalSatisfied()).isFalse();
        assertThat(result.finalAnswer()).contains("Nie mogę rzetelnie zakończyć").doesNotContain("działa poprawnie");
    }

    private ToolResult failedRead() {
        return new ToolResult(false, "pc", "SPOTIFY", "", "", false, List.of(), "Read failed", Map.of(),
                "READ_FAILED", "Read failed", false, "");
    }

    private ToolCallingResult run(ToolResult status, ToolResult devices, ModelResponse... answers) {
        Deque<ModelResponse> turns = new ArrayDeque<>();
        turns.add(new ModelResponse("", "", List.of(new ModelToolCall("c1", "pc__spotify", Map.of("action", "status"))), "tool_calls",
                new ModelUsage(0, 0, 0)));
        turns.add(new ModelResponse("", "", List.of(new ModelToolCall("c2", "pc__spotify", Map.of("action", "devices"))), "tool_calls",
                new ModelUsage(0, 0, 0)));
        turns.addAll(List.of(answers));
        FakePcToolManager tools = new FakePcToolManager(status, devices);
        NativeToolLoopService service = new NativeToolLoopService(
                List.of(new ScriptedProvider(turns)), tools, query -> ToolIntent.NO_TOOL,
                new ToolRuntimeProperties(true, 8, 8, 2, 30, "native"),
                new NoopCognitiveEventBus(), new ToolRuntimeDebugService(), new ObjectMapper(),
                new NativeToolSchemaMapper(registry()),
                new com.jarvis.tools.dataset.StoreAuditDatasetService(new NoopCognitiveEventBus()));
        ToolCallingResult result = service.execute(new ToolCallingRequest("request-spotify", "conversation-1", USER, GOAL, GOAL,
                "Base prompt", new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW), KnowledgeMode.FAST));
        assertThat(tools.actions).containsExactly("status", "devices");
        return result;
    }

    static ToolResult statusConnected() {
        return new ToolResult(true, "pc", "SPOTIFY", "", "", false, List.of(), "PC SPOTIFY finished", Map.of(
                "configured", true, "connected", true, "authorizationPending", false, "lastError", "",
                "configFile", "C:\\Jarvis\\config\\spotify.json",
                "hint", "Set clientId in config/spotify.json, then connect. Playback uses the Windows Spotify app. No Client Secret required."),
                "", "", false, "");
    }

    static ToolResult devices(List<Map<String, Object>> devices) {
        return new ToolResult(true, "pc", "SPOTIFY", "", "", false, List.of(), "PC SPOTIFY finished",
                Map.of("devices", devices), "", "", false, "");
    }

    static Map<String, Object> device(String name, String type, boolean active) {
        return Map.of("id", "dev-" + name, "name", name, "type", type, "active", active, "volume", 50);
    }

    private static ModelResponse answer(String text) {
        return new ModelResponse(text, "", List.of(), "stop", new ModelUsage(0, 0, 0));
    }

    private static ToolRegistry registry() {
        ToolDefinition pc = new ToolDefinition("pc", "Files, shell and apps on the user's Windows PC.", List.of(
                new ToolOperationDefinition("SPOTIFY", "Spotify Web API + Connect.", List.of(
                        new ToolArgumentDefinition("action", "string", true, "status | devices | play | ...")
                ), true, ToolSafetyLevel.WRITE)
        ));
        return new ToolRegistry() {
            @Override
            public List<ToolDefinition> definitions() {
                return List.of(pc);
            }

            @Override
            public String promptSection() {
                return "";
            }
        };
    }

    private static final class ScriptedProvider implements AIProvider {
        private final Deque<ModelResponse> turns;

        private ScriptedProvider(Deque<ModelResponse> turns) {
            this.turns = turns;
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
            return turns.isEmpty() ? new ModelResponse("", "", List.of(), "stop", new ModelUsage(0, 0, 0)) : turns.poll();
        }
    }

    private static final class FakePcToolManager implements ToolManager {
        private final ToolResult status;
        private final ToolResult devices;
        private final List<String> actions = new ArrayList<>();

        private FakePcToolManager(ToolResult status, ToolResult devices) {
            this.status = status;
            this.devices = devices;
        }

        @Override
        public List<JarvisTool> listTools() {
            return List.of();
        }

        @Override
        public Optional<JarvisTool> findTool(String name) {
            return "pc".equalsIgnoreCase(name) ? Optional.of(new JarvisTool() {
                @Override
                public String getName() {
                    return "pc";
                }

                @Override
                public String getDescription() {
                    return "pc";
                }

                @Override
                public ToolResult execute(ToolRequest request) {
                    throw new UnsupportedOperationException();
                }
            }) : Optional.empty();
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            String action = Objects.toString(request.arguments().get("action"), "");
            actions.add(action);
            return switch (action) {
                case "status" -> status;
                case "devices" -> devices;
                default -> throw new AssertionError("Unexpected Spotify action (must not play or log in): " + action);
            };
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
