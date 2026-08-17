package com.jarvis.tools.system;

import com.jarvis.common.ai.BrainType;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.tools.ToolException;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression tests for the {@code system.NOTIFY_USER} tool: it must stream the message on the
 * same live channel final answers use, and its result must never read like a stopping point - a
 * model reading it should be pushed to keep calling tools, not to answer and quit.
 */
class NotifyUserToolTest {

    @Test
    void publishesTheMessageOnTheAnswerStreamAndTellsTheModelToKeepWorking() {
        RecordingCognitiveEventBus bus = new RecordingCognitiveEventBus();
        NotifyUserTool tool = new NotifyUserTool(bus);

        ToolResult result = tool.execute(new ToolRequest("system", "NOTIFY_USER", "conversation-1", "request-1",
                "progress", "", Map.of("message", "Znalazlem procedure, teraz geokoduje sklepy.")));

        assertThat(result.success()).isTrue();
        assertThat(result.message().toLowerCase()).contains("continue");
        assertThat(result.data().get("delivered")).isEqualTo(true);

        assertThat(bus.events).extracting(e -> e.type).containsExactly(
                CognitiveEventType.ANSWER_STARTED,
                CognitiveEventType.STREAMING_STARTED,
                CognitiveEventType.ANSWER_TOKEN,
                CognitiveEventType.TOKEN
        );
        assertThat(bus.events.get(2).metadata.get("text")).isEqualTo("Znalazlem procedure, teraz geokoduje sklepy.\n\n");
        assertThat(bus.events.get(3).metadata.get("text")).isEqualTo("Znalazlem procedure, teraz geokoduje sklepy.\n\n");
    }

    @Test
    void neverPublishesAFinishEventSoTheClientNeverMarksTheTurnOrBubbleComplete() {
        // The Windows client treats both ANSWER_FINISHED and STREAMING_FINISHED as "this turn is
        // over": ANSWER_FINISHED flips requestActive to false (unblocks input, stops the tool
        // heartbeat) and STREAMING_FINISHED alone still drops the "alive" heart animation to idle
        // (JarvisHeartView) and finalizes/closes the message bubble (MainViewModel). Neither is true
        // yet - the tool loop keeps working right after this call - so this tool must never publish
        // either, leaving the bubble open for the eventual real final answer to keep appending to.
        RecordingCognitiveEventBus bus = new RecordingCognitiveEventBus();
        NotifyUserTool tool = new NotifyUserTool(bus);

        tool.execute(new ToolRequest("system", "NOTIFY_USER", "conversation-1", "request-1",
                "progress", "", Map.of("message", "Wciaz pracuje nad grafikiem.")));

        assertThat(bus.events).extracting(e -> e.type)
                .doesNotContain(CognitiveEventType.ANSWER_FINISHED, CognitiveEventType.STREAMING_FINISHED);
    }

    @Test
    void rejectsABlankMessage() {
        NotifyUserTool tool = new NotifyUserTool(new RecordingCognitiveEventBus());

        assertThatThrownBy(() -> tool.execute(new ToolRequest("system", "NOTIFY_USER", "conversation-1", "request-1",
                "progress", "", Map.of("message", "   "))))
                .isInstanceOf(ToolException.class);
    }

    @Test
    void truncatesAnExcessivelyLongMessageInsteadOfFailing() {
        RecordingCognitiveEventBus bus = new RecordingCognitiveEventBus();
        NotifyUserTool tool = new NotifyUserTool(bus);
        String longMessage = "a".repeat(1000);

        ToolResult result = tool.execute(new ToolRequest("system", "NOTIFY_USER", "conversation-1", "request-1",
                "progress", "", Map.of("message", longMessage)));

        assertThat(result.success()).isTrue();
        assertThat(((String) bus.events.get(2).metadata.get("text")).length()).isLessThan(1000);
    }

    private record RecordedEvent(CognitiveEventType type, Map<String, Object> metadata) {
    }

    private static final class RecordingCognitiveEventBus implements CognitiveEventBus {

        private final List<RecordedEvent> events = new ArrayList<>();

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
            events.add(new RecordedEvent(event, metadata == null ? Map.of() : metadata));
        }
    }
}
