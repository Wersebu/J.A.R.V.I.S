package com.jarvis.tools.system;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolException;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolSafetyLevel;
import com.jarvis.tools.schema.ToolSchemaProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Lets the model post a short, visible progress update mid-task without ending its turn - unlike
 * FINAL_ANSWER, calling this tool never stops the native tool loop (see {@link
 * com.jarvis.tools.runtime.NativeToolLoopService}); the loop simply asks the model for its next
 * action right after, the same way it does after any other tool call.
 *
 * <p>This exists because a model previously gave up mid-workflow (found a required knowledge
 * document, then stopped) and told the user a result would "arrive automatically" later. There is
 * no background job system in Core - the request/response cycle is synchronous and ends the moment
 * the model stops calling tools, so that promise was never fulfilled. This tool gives the model a
 * legitimate way to say "still working, here is where I am" while it keeps calling tools toward an
 * actual result, instead of quitting early to say so.</p>
 */
@Service
public class NotifyUserTool implements JarvisTool, ToolSchemaProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotifyUserTool.class);
    private static final String TOOL_NAME = "system";
    private static final String OPERATION = "NOTIFY_USER";
    private static final int MAX_MESSAGE_CHARS = 600;

    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the notify-user tool.
     *
     * @param cognitiveEventBus request-scoped event bus used to stream the update to the same
     *         live channel the final answer streams through
     */
    public NotifyUserTool(CognitiveEventBus cognitiveEventBus) {
        this.cognitiveEventBus = cognitiveEventBus;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Posts a short visible status update to the user while a multi-step task is still "
                + "in progress. Does not end your turn and is never a substitute for the final answer.";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_NAME, getDescription(), List.of(
                new ToolOperationDefinition(OPERATION,
                        "Sends one short, user-facing progress message (in the user's language) while you "
                                + "keep working, e.g. \"Found the audit procedure, now reading it and geocoding "
                                + "the stores.\" This call returns immediately and your turn continues - after "
                                + "it, you must keep calling tools or produce the real final answer. Never use "
                                + "this to announce work that will supposedly finish automatically later: there "
                                + "is no background process, so anything not actually completed inside this "
                                + "same tool loop will never be delivered. Call this at most once or twice per "
                                + "task, and never repeat the same message.",
                        List.of(new ToolArgumentDefinition("message", "string", true,
                                "Short status update for the user, in their language. Not the final answer.")),
                        false, ToolSafetyLevel.READ)
        ));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        String message = text(request.arguments().get("message"));
        if (message.isBlank()) {
            throw new ToolException("NOTIFY_USER requires a non-blank message");
        }
        if (message.length() > MAX_MESSAGE_CHARS) {
            message = message.substring(0, MAX_MESSAGE_CHARS);
        }
        LOGGER.info("[NOTIFY_USER] requestId={} conversationId={} chars={}",
                request.requestId(), request.conversationId(), message.length());
        publishProgressMessage(message);
        return new ToolResult(true, TOOL_NAME, OPERATION, request.requestId(), request.conversationId(),
                false, List.of(),
                "Delivered to the user. Continue the task now - do not repeat this message and do not "
                        + "stop until the task is actually complete.",
                Map.of("delivered", true), "", "", false, "");
    }

    /**
     * Streams the message as a growing paragraph in the still-open assistant bubble - deliberately
     * never finalized here. The Windows client (see {@code MainViewModel} and {@code
     * JarvisHeartView}) treats both {@code ANSWER_FINISHED} and {@code STREAMING_FINISHED} as "the
     * whole turn is over": the former flips {@code requestActive} to false (unblocks input, stops
     * the tool heartbeat), the latter alone still drops the "alive" heart animation to idle. Neither
     * is true yet - the loop keeps calling tools right after this. Leaving the bubble open means the
     * eventual real final answer keeps appending to the same message instead of starting a new one,
     * and only ITS natural STREAMING_FINISHED/ANSWER_FINISHED (at the true end of the request) closes
     * anything - so the UI never looks finished while work is still happening.
     */
    private void publishProgressMessage(String message) {
        String paragraph = message + "\n\n";
        Map<String, Object> tokenMetadata = Map.of(
                "text", paragraph,
                "index", 1,
                "source", "progress-update"
        );
        cognitiveEventBus.publish(CognitiveEventType.ANSWER_STARTED, "ANSWERING", "Progress update started", null,
                Map.of("source", "progress-update"));
        cognitiveEventBus.publish(CognitiveEventType.STREAMING_STARTED, "STREAMING", "Progress update streaming started", null,
                Map.of("source", "progress-update"));
        cognitiveEventBus.publish(CognitiveEventType.ANSWER_TOKEN, "TOKEN", paragraph, null, tokenMetadata);
        cognitiveEventBus.publish(CognitiveEventType.TOKEN, "TOKEN", paragraph, null, tokenMetadata);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }
}
