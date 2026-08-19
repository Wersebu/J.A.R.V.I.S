package com.jarvis.memory.pipeline;

import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.ai.ImageAttachment;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.event.GenerationFinishedEvent;
import com.jarvis.common.event.TokenEvent;
import com.jarvis.common.dto.AttachmentReference;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.dataset.StoreAuditDataset;
import com.jarvis.tools.dataset.StoreAuditDatasetService;
import com.jarvis.tools.runtime.ToolCallingRequest;
import com.jarvis.tools.runtime.ToolCallingResult;
import com.jarvis.tools.runtime.ToolCallingRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes native tool-calling after the main model requested an external capability.
 */
@Service
@Order(92)
public class ToolCallingStage implements PipelineStage {

    private static final Logger LOGGER = LoggerFactory.getLogger(ToolCallingStage.class);
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?iu)(?:\\d{1,3}(?:[ .\\u00A0]?\\d{3})*|\\d+)(?:[,.]\\d{1,2})?\\s*(?:zł|zl|zlotych|pln|usd|eur)"
    );
    private static final String MARKETPLACE_NO_LISTINGS_MESSAGE =
            "Nie udalo mi sie zweryfikowac aktualnych ofert spelniajacych te kryteria.";
    private static final Pattern LEADING_MARKDOWN_FENCE = Pattern.compile("^```[a-zA-Z0-9_-]*\\r?\\n?");
    private static final int FENCE_DETECTION_PROBE_CHARS = 24;
    // Detects a structured envelope's opening even when a model prefixes it with a short prose
    // preamble ("Oto wynik: {"type":...") despite being told to return raw JSON - the confirmed
    // root cause of a raw TOOL_REQUEST envelope leaking into the live-streamed answer, since the
    // plain "starts with {" check below never catches this shape at all.
    private static final Pattern STRUCTURED_ENVELOPE_HINT = Pattern.compile("\\{\\s*\"type\"\\s*:");
    private static final int STRUCTURED_DETECTION_PROBE_CHARS = 48;

    private final ToolCallingRuntime toolCallingRuntime;
    private final List<AIProvider> aiProviders;
    private final MainModelActionParser actionParser;
    private final WebAnswerSourceExtractor sourceExtractor;
    private final StoreAuditDatasetService storeAuditDatasetService;

    /**
     * Creates the tool-calling stage.
     *
     * @param toolCallingRuntime native tool-calling runtime
     * @param storeAuditDatasetService registers real current-message attachment ids for
     *         provenance cross-checking by {@code storeDataset.CREATE_DATASET}
     */
    public ToolCallingStage(
            ToolCallingRuntime toolCallingRuntime,
            List<AIProvider> aiProviders,
            MainModelActionParser actionParser,
            StoreAuditDatasetService storeAuditDatasetService
    ) {
        this.toolCallingRuntime = toolCallingRuntime;
        this.aiProviders = List.copyOf(aiProviders);
        this.actionParser = actionParser;
        this.sourceExtractor = new WebAnswerSourceExtractor();
        this.storeAuditDatasetService = storeAuditDatasetService;
    }

    @Override
    public String name() {
        return "ToolCallingStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        Optional<MainModelAction> responseToolRequest = responseToolRequest(context);
        if (context.response() != null && !context.response().isBlank() && responseToolRequest.isEmpty()) {
            return context;
        }
        boolean metadataToolRequest = "TOOL_REQUEST".equals(String.valueOf(context.metadata().getOrDefault("mainModelAction", "")));
        if (!metadataToolRequest && responseToolRequest.isEmpty()) {
            return context;
        }
        MainModelAction recoveredAction = responseToolRequest.orElse(null);
        if (recoveredAction != null) {
            LOGGER.warn("[TOOL_CALLING_STAGE] requestId={} recovered TOOL_REQUEST from response body; forwarding to NativeToolLoopService",
                    context.requestId());
        }
        logAttachmentProvenance(context);
        List<String> imageAttachmentIds = context.images().stream().map(ImageAttachment::attachmentId).toList();
        storeAuditDatasetService.registerAttachments(context.requestId(), context.conversationId(), imageAttachmentIds);
        if (!imageAttachmentIds.isEmpty()) {
            LOGGER.info("[STORE_AUDIT] requestId={} attachments={}", context.requestId(), imageAttachmentIds.size());
        }
        ToolCallingResult result = toolCallingRuntime.execute(new ToolCallingRequest(
                context.requestId(),
                context.conversationId(),
                context.request().message(),
                toolGoal(context, recoveredAction),
                toolReason(context, recoveredAction),
                toolContext(context, recoveredAction),
                toolBasePrompt(context),
                context.brain(),
                context.effectiveKnowledgeMode(),
                context.images()
        ));
        String answer;
        if (!result.handled()) {
            answer = streamGuidedAnswer(context,
                    "Nie wykonalem narzedzia, poniewaz tool runtime nie zwrocil bezpiecznej akcji do wykonania.",
                    "tool-unhandled");
        } else {
            publishAnswerSources(context, result);
            answer = streamToolFinalAnswer(context, result);
        }
        answer = finalProtocolGuard(context, answer);
        GenerationFinishedEvent finished = GenerationFinishedEvent.create(context.conversationId(), 0, context.brain().type(),
                context.model(), null, Math.max(1, answer.length() / 4), null);
        return context.withResponse(answer, finished)
                .withMetadata("toolCallingHandled", true)
                .withMetadata("toolCallingSteps", result.steps().size());
    }

    private Optional<MainModelAction> responseToolRequest(PipelineContext context) {
        if (context.response() == null || context.response().isBlank()) {
            return Optional.empty();
        }
        try {
            MainModelAction action = actionParser.parse(context.response());
            return action.type() == MainModelActionType.TOOL_REQUEST ? Optional.of(action) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private String toolGoal(PipelineContext context, MainModelAction recoveredAction) {
        if (recoveredAction != null && !recoveredAction.goal().isBlank()) {
            return recoveredAction.goal();
        }
        return String.valueOf(context.metadata().getOrDefault("toolGoal", ""));
    }

    private String toolReason(PipelineContext context, MainModelAction recoveredAction) {
        if (recoveredAction != null && !recoveredAction.reason().isBlank()) {
            return recoveredAction.reason();
        }
        return String.valueOf(context.metadata().getOrDefault("toolReason", ""));
    }

    private Map<String, Object> toolContext(PipelineContext context, MainModelAction recoveredAction) {
        if (recoveredAction != null && !recoveredAction.context().isEmpty()) {
            return recoveredAction.context();
        }
        return metadataMap(context.metadata().get("toolContext"));
    }

    /**
     * Last, unconditional guard immediately before this stage's answer becomes the response - every
     * earlier protection (structured-detection in {@link #handleToolAnswerToken}, {@link
     * #parsedStructuredToolAnswer}, the final-synthesis re-entry in {@link
     * #streamToolFinalSynthesis}) already exists to stop a raw {@code TOOL_REQUEST} envelope from
     * reaching the user, but each covers one specific code path - this catches whatever slips past
     * every one of them, no matter the reason, since a raw protocol envelope must never reach the
     * user under any circumstance. A bounded re-entry into the tool runtime already happened
     * upstream where one was possible ({@link #streamToolFinalSynthesis}'s own retry budget); by
     * the time content reaches here, that budget is already spent, so this only ever needs to
     * choose an honest failure message over leaking raw JSON.
     *
     * @param context pipeline context
     * @param candidate this stage's answer, about to become the user-facing response
     * @return {@code candidate} unchanged unless it is itself a structured envelope, in which case
     *         a FINAL_ANSWER/CLARIFICATION is unwrapped and a TOOL_REQUEST is replaced with an
     *         honest, plain-text explanation of what remains unfinished
     */
    private String finalProtocolGuard(PipelineContext context, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return candidate;
        }
        try {
            MainModelAction action = actionParser.parse(candidate);
            return switch (action.type()) {
                case FINAL_ANSWER -> action.answer();
                case CLARIFICATION -> action.question();
                case TOOL_REQUEST -> {
                    LOGGER.warn("[TOOL_CALLING_STAGE] requestId={} FINAL_PROTOCOL_GUARD blocked a TOOL_REQUEST "
                                    + "envelope from reaching the user goal=\"{}\"",
                            context.requestId(), action.goal());
                    yield incompleteWorkflowMessage(context);
                }
            };
        } catch (RuntimeException exception) {
            // candidate genuinely is not a JSON envelope (no {...} to extract) - ordinary plain
            // user-facing text, returned unchanged.
            return candidate;
        }
    }

    /**
     * Builds an honest, plain-text explanation of what is still unfinished for a workflow that
     * leaked a TOOL_REQUEST instead of a real answer - naming the exact dataset stage when a Store
     * Audit dataset exists for this conversation, instead of a generic apology.
     *
     * @param context pipeline context
     * @return plain-text failure message, never protocol JSON
     */
    private String incompleteWorkflowMessage(PipelineContext context) {
        Optional<StoreAuditDataset> dataset = storeAuditDatasetService.findLatestForConversation(context.conversationId());
        if (dataset.isPresent()) {
            StoreAuditDataset value = dataset.get();
            return "Nie udalo mi sie ukonczyc zadania, poniewaz zestaw danych Store Audit (stage=" + value.stage()
                    + ", " + value.stores().size() + " rekord(y)) nie zostal jeszcze doprowadzony do konca. "
                    + "Sprobuj poprosic ponownie, aby kontynuowac od tego miejsca.";
        }
        return "Nie udalo mi sie ukonczyc zadania w tej turze - narzedzia nie zwrocily czytelnej tresci koncowej odpowiedzi.";
    }

    /**
     * Logs a diagnostic line comparing the full current-message attachment set against the
     * image-only subset actually used for provenance registration, so a future test run can
     * pinpoint whether a provenance mismatch originates from upload, {@code ImageAttachmentStage},
     * context propagation, or the model/tool layer - counts and real ids only, never base64 payloads.
     *
     * @param context pipeline context
     */
    private void logAttachmentProvenance(PipelineContext context) {
        List<String> registeredIds = context.request().attachments().stream().map(AttachmentReference::attachmentId).toList();
        List<String> imageContextIds = context.images().stream().map(ImageAttachment::attachmentId).toList();
        boolean mappingConsistent = new LinkedHashSet<>(registeredIds).containsAll(imageContextIds);
        LOGGER.info("[ATTACHMENT_PROVENANCE] requestId={} registeredAttachments={} imageAttachments={} registeredIds={} imageContextIds={} mappingConsistent={}",
                context.requestId(), registeredIds.size(), imageContextIds.size(), registeredIds, imageContextIds, mappingConsistent);
    }

    /**
     * Above this many times a final-synthesis call (the tool-less narration turn used when the
     * native tool loop itself returned no final content) returns another {@code TOOL_REQUEST}
     * envelope as text, this stage stops re-entering the tool runtime and falls through to the
     * ordinary honest fallback text - see {@link #streamToolFinalSynthesis}.
     */
    private static final int MAX_FINAL_SYNTHESIS_REENTRIES = 1;

    private String streamToolFinalAnswer(PipelineContext context, ToolCallingResult result) {
        return streamToolFinalAnswer(context, result, MAX_FINAL_SYNTHESIS_REENTRIES);
    }

    private String streamToolFinalAnswer(PipelineContext context, ToolCallingResult result, int synthesisRetriesLeft) {
        List<Map<String, Object>> verifiedListings = marketplaceListings(result);
        if (!verifiedListings.isEmpty()) {
            // Verified listings are strictly more trustworthy than freeform model text (price/URL
            // come straight from a verified record) - this deterministic table always wins first.
            return publishBufferedFallback(context, deterministicMarketplaceTable(result, verifiedListings), "verified-marketplace");
        }
        if (result.finalAnswer() != null && !result.finalAnswer().isBlank()) {
            // The model already produced this answer inside the native tool loop, with full tool
            // access and full observation of every tool result. Re-asking a second, tool-less
            // model turn here would discard that answer and give the model no way to act on
            // anything it realizes it still needs at that point. This also matters when a marketplace
            // search happened to be part of a mixed-purpose task (e.g. a routing request that
            // incidentally touched SEARCH_MARKETPLACE) and found no listings - the model's real
            // answer for the rest of the task must not be discarded just because that one sub-call
            // came up empty.
            //
            // The native loop's own plain-text final turn is sometimes still the structured
            // {"type":"FINAL_ANSWER","answer":"..."} envelope the model was taught to use elsewhere
            // (unlike the separate synthesis path below, which already unwraps this via the
            // structured streaming parser) - unwrap it here too so the raw JSON never reaches the
            // user. parsedStructuredToolAnswer() falls through to the original text unchanged for
            // genuinely plain answers (no leading/trailing braces to parse).
            return publishBufferedFallback(context, parsedStructuredToolAnswer(result.finalAnswer(), result.finalAnswer()), "tool-fallback");
        }
        if (marketplaceResearch(result)) {
            // Pure marketplace research with no listings and no other model answer available -
            // the honest deterministic failure text is still the right answer here.
            return publishBufferedFallback(context, MARKETPLACE_NO_LISTINGS_MESSAGE, "verified-marketplace");
        }
        publish(context, CognitiveEventType.FINAL_SYNTHESIS_STARTED, "STARTED",
                "Final answer synthesis from tool results started", Map.of(
                        "toolResults", result.results().size(),
                        "toolSteps", result.steps().size(),
                        "model", context.model()
                ));
        String prompt = toolFinalAnswerPrompt(context, result);
        String fallback = fallbackToolAnswer(context, result);
        String answer;
        try {
            answer = streamToolFinalSynthesis(context, prompt, fallback, synthesisRetriesLeft);
        } catch (AIProviderException exception) {
            answer = publishBufferedFallback(context, fallback, "tool-fallback");
        }
        publish(context, CognitiveEventType.FINAL_SYNTHESIS_FINISHED, "FINISHED",
                "Final answer synthesis from tool results finished", Map.of(
                        "toolResults", result.results().size(),
                        "characters", answer.length(),
                        "model", context.model()
                ));
        return answer;
    }

    private String streamGuidedAnswer(PipelineContext context, String guidance, String source) {
        String prompt = toolBasePrompt(context)
                + "\n\nWrite a concise user-facing answer in the user's language."
                + "\nReturn plain text only."
                + "\n\nAnswer guidance:\n" + guidance;
        try {
            return streamPrompt(context, prompt, guidance);
        } catch (AIProviderException exception) {
            return publishBufferedFallback(context, guidance, source);
        }
    }

    private String streamPrompt(PipelineContext context, String prompt, String fallback) {
        ToolAnswerStreamState streamState = new ToolAnswerStreamState();
        selectProvider(context).stream(context.conversationId(), context.brain(), prompt, AIJobType.MAIN_MODEL, event -> {
            if (event instanceof TokenEvent tokenEvent) {
                handleToolAnswerToken(context, tokenEvent.text(), streamState);
            }
            if (event instanceof GenerationFinishedEvent finishedEvent) {
                streamState.finishedEvent = finishedEvent;
            }
        });
        String answer = finishToolAnswerStream(context, streamState, fallback);
        return answer.isBlank() ? fallback : answer;
    }

    /**
     * Streams the tool-less final-synthesis narration call, with one important difference from
     * {@link #streamPrompt}: this call's whole reason for existing is that the native tool loop
     * itself returned no final content, so if its own output turns out to be another {@code
     * TOOL_REQUEST} envelope (a model habit, not a deliberate "give up" signal), that must be
     * executed - re-entering the real tool runtime - rather than turned into an apology. Nothing is
     * streamed to the user for a TOOL_REQUEST-typed structured response (see {@link
     * StreamingStructuredResponseParser}, which only streams the answer/question field), so this
     * check runs before anything has been shown to the user.
     *
     * @param context pipeline context
     * @param prompt final-synthesis prompt
     * @param fallback safe fallback text if nothing usable comes back
     * @param retriesLeft remaining re-entry attempts, bounded by {@link #MAX_FINAL_SYNTHESIS_REENTRIES}
     * @return final user-facing answer text
     */
    private String streamToolFinalSynthesis(PipelineContext context, String prompt, String fallback, int retriesLeft) {
        ToolAnswerStreamState streamState = new ToolAnswerStreamState();
        selectProvider(context).stream(context.conversationId(), context.brain(), prompt, AIJobType.MAIN_MODEL, event -> {
            if (event instanceof TokenEvent tokenEvent) {
                handleToolAnswerToken(context, tokenEvent.text(), streamState);
            }
            if (event instanceof GenerationFinishedEvent finishedEvent) {
                streamState.finishedEvent = finishedEvent;
            }
        });
        if (streamState.structured && streamState.answer.isEmpty() && retriesLeft > 0) {
            // streamState.raw is only a pre-decision scratch buffer - it is cleared the moment
            // structured mode is decided (see handleToolAnswerToken). The parser's own raw()
            // accumulator is the one that keeps the full structured content across every token.
            Optional<MainModelAction> reentry = detectToolRequestReentry(streamState.parser.raw());
            if (reentry.isPresent()) {
                MainModelAction action = reentry.get();
                LOGGER.info("[TOOL_CALLING_STAGE] requestId={} REENTER_TOOL_LOOP reason=FINAL_SYNTHESIS_RETURNED_TOOL_REQUEST goal=\"{}\"",
                        context.requestId(), action.goal());
                publish(context, CognitiveEventType.TOOL_VERIFICATION_STARTED, "REENTER_TOOL_LOOP",
                        "Final synthesis requested another tool action; re-entering the tool loop", Map.of(
                                "goal", action.goal(), "reason", action.reason()));
                ToolCallingResult reentryResult = toolCallingRuntime.execute(new ToolCallingRequest(
                        context.requestId(), context.conversationId(), context.request().message(),
                        action.goal(), action.reason(), action.context(), toolBasePrompt(context),
                        context.brain(), context.effectiveKnowledgeMode(), context.images()));
                if (reentryResult.handled()) {
                    publishAnswerSources(context, reentryResult);
                    return streamToolFinalAnswer(context, reentryResult, retriesLeft - 1);
                }
            }
        }
        String answer = finishToolAnswerStream(context, streamState, fallback);
        return answer.isBlank() ? fallback : answer;
    }

    /**
     * Returns the parsed action only when {@code raw} is a {@code TOOL_REQUEST} envelope - used to
     * decide whether a final-synthesis turn should re-enter the tool runtime instead of being
     * treated as (or converted into) user-facing text.
     *
     * @param raw raw structured model output
     * @return the parsed TOOL_REQUEST action, if that is what {@code raw} is
     */
    private Optional<MainModelAction> detectToolRequestReentry(String raw) {
        try {
            MainModelAction action = actionParser.parse(raw);
            return action.type() == MainModelActionType.TOOL_REQUEST ? Optional.of(action) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Map<String, Object> metadataMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private void handleToolAnswerToken(PipelineContext context, String token, ToolAnswerStreamState streamState) {
        if (token == null || token.isEmpty()) {
            return;
        }
        if (!streamState.modeDecided) {
            streamState.raw.append(token);
            String stripped = streamState.raw.toString().stripLeading();
            if (stripped.isEmpty()) {
                return;
            }
            if (!stripped.startsWith("{") && isUnresolvedFencePrefix(stripped)) {
                // A leading markdown code fence (```json, ``` ...) is a common way models wrap
                // the structured FINAL_ANSWER envelope despite being told to return raw JSON -
                // wait for a few more characters before committing to "plain text" mode, or the
                // fence and raw JSON leak straight into the chat as a rendered code block instead
                // of the actual answer text.
                return;
            }
            String unfenced = LEADING_MARKDOWN_FENCE.matcher(stripped).replaceFirst("");
            if (!unfenced.startsWith("{")) {
                Matcher hintMatcher = STRUCTURED_ENVELOPE_HINT.matcher(unfenced);
                if (hintMatcher.find()) {
                    // A structured envelope arrived after a prose preamble - discard that prose
                    // (never shown to the user) and treat only the envelope onward as the
                    // structured content, or the preamble would stream as narration and then the
                    // envelope would still leak in raw once the plain-text path re-took over.
                    unfenced = unfenced.substring(hintMatcher.start());
                } else if (unfenced.length() < STRUCTURED_DETECTION_PROBE_CHARS) {
                    // Not enough content yet to rule out a structured envelope arriving after more
                    // prose - keep waiting instead of committing to "plain text" too early.
                    return;
                }
            }
            streamState.structured = unfenced.startsWith("{");
            streamState.modeDecided = true;
            if (streamState.structured) {
                StreamingStructuredResponseParser.ParserUpdate update = streamState.parser.accept(unfenced);
                streamState.raw.setLength(0);
                update.detectedType().ifPresent(type -> publishStructuredToolAnswerDetected(context, type));
                if (update.streamedText() != null && !update.streamedText().isEmpty()) {
                    streamToolAnswerChunk(context, update.streamedText(), streamState);
                }
                return;
            }
            streamToolAnswerChunk(context, streamState.raw.toString(), streamState);
            streamState.raw.setLength(0);
            return;
        }
        if (!streamState.structured) {
            streamToolAnswerChunk(context, token, streamState);
            return;
        }
        StreamingStructuredResponseParser.ParserUpdate update = streamState.parser.accept(token);
        update.detectedType().ifPresent(type -> publishStructuredToolAnswerDetected(context, type));
        if (update.streamedText() != null && !update.streamedText().isEmpty()) {
            streamToolAnswerChunk(context, update.streamedText(), streamState);
        }
    }

    /**
     * Returns true while {@code stripped} could still turn into a leading markdown code fence
     * (e.g. {@code ```json\n{...}}) as more tokens arrive, so the caller should keep waiting
     * instead of deciding "not structured" too early.
     *
     * @param stripped accumulated answer text so far, leading-whitespace-stripped, known not to
     *         start with {@code {}
     * @return true when the fence opening is still unresolved
     */
    private boolean isUnresolvedFencePrefix(String stripped) {
        int backticks = 0;
        while (backticks < stripped.length() && backticks < 3 && stripped.charAt(backticks) == '`') {
            backticks++;
        }
        if (backticks < 3) {
            return backticks == stripped.length() && stripped.length() < 3;
        }
        int newlineIndex = stripped.indexOf('\n', 3);
        if (newlineIndex < 0) {
            return stripped.length() < FENCE_DETECTION_PROBE_CHARS;
        }
        // The fence-open line's newline arrived, but nothing after it has streamed in yet -
        // keep waiting for at least one more character instead of deciding based on an empty
        // remainder (which would otherwise always look like "not structured").
        return newlineIndex + 1 >= stripped.length() && stripped.length() < FENCE_DETECTION_PROBE_CHARS;
    }

    private String finishToolAnswerStream(PipelineContext context, ToolAnswerStreamState streamState, String fallback) {
        String answer = streamState.answer.toString();
        if (streamState.structured && answer.isBlank()) {
            // streamState.raw is only a pre-decision scratch buffer, cleared the moment structured
            // mode is decided (see handleToolAnswerToken) - parser.raw() is what actually holds the
            // full structured content accumulated across every token, single- or multi-token alike.
            answer = parsedStructuredToolAnswer(streamState.parser.raw(), fallback);
            if (!answer.isBlank()) {
                streamToolAnswerChunk(context, answer, streamState);
            }
        }
        if (!streamState.structured && !streamState.raw.isEmpty()) {
            answer = streamState.raw.toString();
            streamToolAnswerChunk(context, answer, streamState);
        }
        if (answer.isBlank()) {
            answer = fallback;
            streamToolAnswerChunk(context, answer, streamState);
        }
        long durationMs = streamState.answerStartedNano == 0L ? 0L : (System.nanoTime() - streamState.answerStartedNano) / 1_000_000L;
        publish(context, CognitiveEventType.ANSWER_FINISHED, "FINISHED", "Tool answer finished", Map.of(
                "durationMs", durationMs,
                "characters", answer.length(),
                "tokens", Math.max(1, answer.length() / 4),
                "source", "tool-final-answer"
        ));
        publish(context, CognitiveEventType.STREAMING_FINISHED, "FINISHED", "Tool answer streaming finished", Map.of(
                "generationTimeMs", streamState.finishedEvent == null ? 0 : streamState.finishedEvent.generationTimeMs(),
                "promptTokens", streamState.finishedEvent == null || streamState.finishedEvent.promptTokens() == null ? 0 : streamState.finishedEvent.promptTokens(),
                "completionTokens", streamState.finishedEvent == null || streamState.finishedEvent.completionTokens() == null
                        ? Math.max(1, answer.length() / 4)
                        : streamState.finishedEvent.completionTokens(),
                "tokensStreamed", Math.max(1, streamState.answerChunks),
                "tokensPerSecond", streamState.finishedEvent == null || streamState.finishedEvent.tokensPerSecond() == null ? 0.0d : streamState.finishedEvent.tokensPerSecond(),
                "source", "tool-final-answer"
        ));
        return answer;
    }

    private String parsedStructuredToolAnswer(String raw, String fallback) {
        try {
            MainModelAction action = actionParser.parse(raw);
            return switch (action.type()) {
                case FINAL_ANSWER -> action.answer();
                case CLARIFICATION -> action.question();
                // A TOOL_REQUEST parsed out of what was supposed to be the tool loop's final
                // plain-text content is never legitimate user-facing text - it is protocol JSON
                // the model wrote out of habit after the loop already ended (usually with a prose
                // preamble in front of it, which is exactly why actionParser.parse() above still
                // succeeded: it strips surrounding text and parses just the {...} block). The
                // caller passes the *same* raw text as `fallback`, so returning it here would leak
                // that JSON scaffolding verbatim to the user - never do that; say so honestly
                // instead.
                case TOOL_REQUEST -> "Zakonczylem prace z narzedziami, ale nie otrzymalem czytelnej tresci koncowej odpowiedzi.";
            };
        } catch (RuntimeException exception) {
            // raw genuinely was not a JSON envelope at all (no {...} to extract) - it is plain
            // prose, and fallback (typically the same raw text) is the correct thing to show.
            return fallback;
        }
    }

    private void publishStructuredToolAnswerDetected(PipelineContext context, MainModelActionType type) {
        publish(context, CognitiveEventType.STRUCTURED_RESPONSE_DETECTED, type.name(),
                "Structured tool final response detected", Map.of(
                        "type", type.name(),
                        "model", context.model(),
                        "source", "tool-final-answer"
                ));
    }

    private void streamToolAnswerChunk(PipelineContext context, String chunk, ToolAnswerStreamState streamState) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        if (!streamState.answerStarted) {
            streamState.answerStarted = true;
            streamState.answerStartedNano = System.nanoTime();
            publish(context, CognitiveEventType.ANSWER_STARTED, "ANSWERING", "Tool answer started", Map.of(
                    "model", context.model(),
                    "source", "tool-final-answer"
            ));
            publish(context, CognitiveEventType.STREAMING_STARTED, "STREAMING", "Tool answer streaming started", Map.of(
                    "model", context.model(),
                    "source", "tool-final-answer"
            ));
        }
        streamState.answerChunks++;
        streamState.answer.append(chunk);
        publish(context, CognitiveEventType.ANSWER_TOKEN, "TOKEN", chunk, Map.of(
                "text", chunk,
                "index", streamState.answerChunks,
                "source", "tool-final-answer"
        ));
        publish(context, CognitiveEventType.TOKEN, "TOKEN", chunk, Map.of(
                "text", chunk,
                "index", streamState.answerChunks,
                "source", "tool-final-answer"
        ));
    }

    private String publishBufferedFallback(PipelineContext context, String answer, String source) {
        publish(context, CognitiveEventType.ANSWER_STARTED, "ANSWERING", "Fallback answer started", Map.of(
                "model", context.model(),
                "source", source
        ));
        publish(context, CognitiveEventType.ANSWER_TOKEN, "TOKEN", answer, Map.of(
                "text", answer,
                "index", 1,
                "source", source
        ));
        publish(context, CognitiveEventType.ANSWER_FINISHED, "FINISHED", "Fallback answer finished", Map.of(
                "durationMs", 0,
                "characters", answer.length(),
                "tokens", Math.max(1, answer.length() / 4),
                "source", source
        ));
        publish(context, CognitiveEventType.STREAMING_FINISHED, "FINISHED", "Fallback streaming finished", Map.of(
                "generationTimeMs", 0,
                "promptTokens", 0,
                "completionTokens", Math.max(1, answer.length() / 4),
                "tokensStreamed", 1,
                "tokensPerSecond", 0.0d,
                "source", source
        ));
        return answer;
    }

    private String toolFinalAnswerPrompt(PipelineContext context, ToolCallingResult result) {
        return toolBasePrompt(context)
                + "\n\nTool execution returned control. Determine whether the user's task is actually complete "
                + "based on verified state and tool observations below - do not assume it is just because tools "
                + "ran. If another external action is still required to finish the task, say in plain text "
                + "exactly what remains and why you cannot finish yet, instead of presenting an incomplete "
                + "result as done."
                + "\nDo not reveal hidden chain-of-thought. You may briefly mention what was done."
                + "\nIf approval is required, clearly tell the user that a draft is waiting for approval."
                + "\nIf WebSearchTool results are present, answer only from those observations."
                + "\nIf WebSearchTool or READ_WEB_PAGE succeeded, never claim that you have no internet access."
                + "\nIf a specific page was unreadable, blocked, or did not expose the requested detail, say that exact limitation instead."
                + "\nIf page observations include structured data, meta data, prices, currencies, or offer details, use those details directly."
                + "\nFor marketplace/current-price answers, VERIFIED_MARKETPLACE_LISTINGS are authoritative."
                + "\nIf VERIFIED_MARKETPLACE_LISTINGS exists, build marketplace tables only from that block."
                + "\nNever build marketplace listing rows from raw SEARCH_WEB snippets, acceptedResults, category pages, or unread links."
                + "\nNever mix listing fields. Each row must use title, price, condition, and URL from the same verified listing record."
                + "\nMarketplace tables must include a Link column and copy each listing URL exactly."
                + "\nDo not invent listing location, seller, stock, or condition unless that field exists in a verified listing."
                + "\nFor market-price questions, summarize the verified sample size. If fewer listings were verified than requested, say exactly how many were verified."
                + "\nIf the user asked for a link, URL, source, concrete listing, or where to buy, include the best verified URL in the answer instead of giving only a price."
                + "\nUse links only from TRUSTED_WEB_SOURCES below. Copy URLs exactly. Never synthesize URLs from titles, categories, snippets, or item IDs."
                + "\nIf TRUSTED_WEB_SOURCES only contains a category/search URL, label it as a search/results page, not a concrete listing."
                + "\nIf no exact listing URL is present, say that an exact listing URL was not verified instead of inventing one."
                + "\nNever answer with internal tool status text such as \"Web search finished\", \"Web page read finished\", or \"Tool finished\"."
                + "\nFor READ_WEB_PAGE observations, extract the user-facing answer from the page title/content/data, not from the tool status message."
                + "\nDo not invent, rewrite, or append source URLs. Use only URLs present in tool observations."
                + "\nIf any GEOCODE/GEOCODE_DATASET observation reported an address as ambiguous, not confidently "
                + "resolved, or not found, name that exact address in the answer and say it needs the user's "
                + "confirmation - never silently pick one of the candidates and present it as settled."
                + "\nIf a storeDataset was used, state the exact record count from the dataset (e.g. \"23/23 "
                + "sklepow\") and explicitly name any record that ended up unresolved, unscheduled, or excluded - "
                + "never present a schedule as complete without checking it against the dataset's real count."
                + "\nKeep the answer concise, natural, and in the user's language."
                + "\nReturn plain text only."
                + "\n\nUser request:\n" + context.request().message()
                + "\n\nVERIFIED_MARKETPLACE_LISTINGS:\n" + verifiedMarketplaceListings(result)
                + "\n\nTRUSTED_WEB_SOURCES:\n" + trustedSourceManifest(result)
                + "\n\nTool observations:\n" + toolObservations(result, marketplaceResearch(result))
                + "\n\nExisting final answer guidance:\n" + safe(result.finalAnswer());
    }

    /**
     * Builds the deterministic verified-listings table. Only called once the caller has already
     * confirmed {@code listings} is non-empty - unlike the removed {@code deterministicMarketplaceAnswer},
     * this never decides on its own whether marketplace research "failed"; that decision now lives
     * in {@link #streamToolFinalAnswer}, which only falls back to the plain failure message when
     * there is truly no other answer to give (see {@link #MARKETPLACE_NO_LISTINGS_MESSAGE}).
     */
    private String deterministicMarketplaceTable(ToolCallingResult result, List<Map<String, Object>> listings) {
        StringBuilder builder = new StringBuilder();
        int requested = requestedListingCount(result);
        builder.append("Zweryfikowalem ")
                .append(listings.size())
                .append(requested > 0 ? " z " + requested : "")
                .append(" ofert na podstawie odczytanych stron ogloszen.\n\n")
                .append("| # | Tytul | Cena | Stan | Link |\n")
                .append("|---|---|---:|---|---|\n");
        int index = 1;
        for (Map<String, Object> listing : listings) {
            builder.append("| ")
                    .append(index++)
                    .append(" | ")
                    .append(escapeTable(text(listing.get("title"))))
                    .append(" | ")
                    .append(escapeTable(text(listing.get("price"))))
                    .append(" ")
                    .append(escapeTable(text(listing.get("currency"))))
                    .append(" | ")
                    .append(escapeTable(text(listing.get("condition"))))
                    .append(" | ")
                    .append(text(listing.get("url")))
                    .append(" |\n");
        }
        builder.append("\nCeny pochodza z konkretnych odczytanych stron ofert, nie z samych wynikow wyszukiwania.");
        return builder.toString();
    }

    private boolean marketplaceResearch(ToolCallingResult result) {
        for (ToolResult toolResult : result.results()) {
            if (Boolean.TRUE.equals(toolResult.data().get("marketplaceResearch"))) {
                return true;
            }
            Object listings = toolResult.data().get("marketplaceListings");
            if (listings instanceof List<?> list && !list.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private int requestedListingCount(ToolCallingResult result) {
        for (ToolResult toolResult : result.results()) {
            Object value = toolResult.data().get("targetListingCount");
            if (value instanceof Number number) {
                return number.intValue();
            }
            value = toolResult.data().get("requestedListingCount");
            if (value instanceof Number number) {
                return number.intValue();
            }
        }
        return 0;
    }

    private String verifiedMarketplaceListings(ToolCallingResult result) {
        List<Map<String, Object>> listings = marketplaceListings(result);
        if (listings.isEmpty()) {
            return "- No verified marketplace listings.\n";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (Map<String, Object> listing : listings) {
            builder.append(index++).append(".\n")
                    .append("Title: ").append(text(listing.get("title"))).append("\n")
                    .append("Price: ").append(text(listing.get("price"))).append("\n")
                    .append("Currency: ").append(text(listing.get("currency"))).append("\n")
                    .append("Condition: ").append(text(listing.get("condition"))).append("\n")
                    .append("URL: ").append(text(listing.get("url"))).append("\n")
                    .append("Verified: ").append(text(listing.get("verified"))).append("\n")
                    .append("HTTP status: ").append(text(listing.get("httpStatus"))).append("\n\n");
        }
        return builder.toString();
    }

    private List<Map<String, Object>> marketplaceListings(ToolCallingResult result) {
        List<Map<String, Object>> listings = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ToolResult toolResult : result.results()) {
            Object raw = toolResult.data().get("marketplaceListings");
            if (!(raw instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map) || !Boolean.TRUE.equals(map.get("verified"))) {
                    continue;
                }
                String url = text(map.get("url"));
                if (url.isBlank() || !seen.add(url)) {
                    continue;
                }
                Map<String, Object> copy = new java.util.LinkedHashMap<>();
                map.forEach((key, value) -> copy.put(text(key), value));
                listings.add(copy);
            }
        }
        return List.copyOf(listings);
    }

    private String trustedSourceManifest(ToolCallingResult result) {
        List<Map<String, Object>> sources = sourceExtractor.extract(result, 12);
        if (sources.isEmpty()) {
            return "- No trusted web URLs were verified.\n";
        }
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (Map<String, Object> source : sources) {
            String title = text(source.get("title"));
            String domain = text(source.get("domain"));
            String url = text(source.get("url"));
            builder.append(index++).append(". ");
            if (!title.isBlank()) {
                builder.append(title).append(" | ");
            }
            if (!domain.isBlank()) {
                builder.append(domain).append(" | ");
            }
            builder.append(url).append("\n");
        }
        return builder.toString();
    }

    private String toolObservations(ToolCallingResult result, boolean marketplaceResearch) {
        if (marketplaceResearch) {
            return marketplaceEvidencePackage(result);
        }
        StringBuilder builder = new StringBuilder();
        for (ToolResult toolResult : result.results()) {
            builder.append("- Tool: ").append(toolResult.tool())
                    .append(", operation: ").append(toolResult.operation())
                    .append(", success: ").append(toolResult.success())
                    .append(", approvalRequired: ").append(toolResult.requiresApproval())
                    .append(", message: ").append(toolResult.message())
                    .append(", data: ").append(toolResult.data())
                    .append("\n");
        }
        if (builder.isEmpty()) {
            builder.append("- No concrete tool result was produced.\n");
        }
        return builder.toString();
    }

    private String marketplaceEvidencePackage(ToolCallingResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("MARKETPLACE_RESEARCH: true\n")
                .append("REQUESTED: ").append(requestedListingCount(result)).append("\n")
                .append("VERIFIED: ").append(marketplaceListings(result).size()).append("\n")
                .append("LISTINGS:\n")
                .append(verifiedMarketplaceListings(result))
                .append("STATUS:\n");
        for (ToolResult toolResult : result.results()) {
            Object statuses = toolResult.data().get("listingStatusCounts");
            if (statuses != null) {
                builder.append(statuses).append("\n");
            }
        }
        return builder.toString();
    }

    private String escapeTable(String value) {
        return value.replace("|", "\\|").replace("\n", " ").strip();
    }

    private void publishAnswerSources(PipelineContext context, ToolCallingResult result) {
        List<Map<String, Object>> sources = sourceExtractor.extract(result);
        if (sources.isEmpty()) {
            return;
        }
        publish(context, CognitiveEventType.ANSWER_SOURCES, "READY", "Trusted answer sources ready", Map.of(
                "sources", sources,
                "count", sources.size(),
                "source", "web-search-tool",
                "limit", WebAnswerSourceExtractor.DEFAULT_LIMIT
        ));
    }

    private String fallbackToolAnswer(PipelineContext context, ToolCallingResult result) {
        if (result.finalAnswer() != null && !result.finalAnswer().isBlank()) {
            return result.finalAnswer();
        }
        boolean linkRequest = isLinkRequest(context.request().message());
        Optional<ToolResult> readPage = lastWebResult(result, "READ_WEB_PAGE");
        if (readPage.isPresent()) {
            return fallbackWebPageAnswer(readPage.get(), linkRequest);
        }
        Optional<ToolResult> search = lastWebResult(result, "SEARCH_WEB");
        if (search.isPresent()) {
            return fallbackWebSearchAnswer(search.get(), linkRequest);
        }
        for (int index = result.results().size() - 1; index >= 0; index--) {
            ToolResult toolResult = result.results().get(index);
            if (toolResult.requiresApproval()) {
                return "Przygotowalem szkic zmiany. Czeka na zatwierdzenie.";
            }
            // A failed result's "message" (e.g. invalidResult's literal "Invalid native tool
            // call", or duplicateResult's/noProgressResult's internal loop-safety messages) is
            // diagnostic text for logs, never something a user should see presented as the
            // assistant's actual answer - only a successful tool's message is eligible here.
            if (!toolResult.success()) {
                continue;
            }
            // KnowledgeTool's "message" is always a generic operation-status label ("Document
            // read", "Search finished", "Folder listed", ...), never real user-facing content -
            // it must never be surfaced as if it were an actual answer. Other tools (web,
            // location, storeDataset) put genuinely informative text in "message", so only they
            // are eligible fallback candidates here.
            if ("knowledge".equalsIgnoreCase(toolResult.tool())) {
                continue;
            }
            if (!toolResult.message().isBlank() && !isTechnicalToolMessage(toolResult.message())) {
                return toolResult.message();
            }
        }
        return "Zakonczylem prace z narzedziami, ale model nie zwrocil tresci odpowiedzi.";
    }

    private Optional<ToolResult> lastWebResult(ToolCallingResult result, String operation) {
        for (int index = result.results().size() - 1; index >= 0; index--) {
            ToolResult toolResult = result.results().get(index);
            if (toolResult.success()
                    && "web".equalsIgnoreCase(toolResult.tool())
                    && operation.equalsIgnoreCase(toolResult.operation())) {
                return Optional.of(toolResult);
            }
        }
        return Optional.empty();
    }

    private String fallbackWebPageAnswer(ToolResult toolResult, boolean linkRequest) {
        String title = text(toolResult.data().get("title"));
        String url = text(toolResult.data().get("url"));
        if (linkRequest && !url.isBlank()) {
            Optional<SearchFallbackCandidate> extractedLink = firstPageLink(toolResult);
            if (extractedLink.isPresent()) {
                SearchFallbackCandidate candidate = extractedLink.get();
                return candidate.title().isBlank()
                        ? "Najbardziej pasujacy link: " + candidate.url()
                        : "Najbardziej pasujacy link: " + candidate.title() + " - " + candidate.url();
            }
            return title.isBlank()
                    ? "Zweryfikowalem tylko strone wynikow: " + url
                    : "Zweryfikowalem tylko strone wynikow: " + title + " - " + url;
        }
        String content = text(toolResult.data().get("content"));
        String price = firstPrice(content);
        if (!price.isBlank()) {
            if (!title.isBlank()) {
                return title + " kosztuje " + price + ".";
            }
            return "Znalazlem cene: " + price + ".";
        }
        if (!content.isBlank()) {
            return "Odczytalem strone, ale nie znalazlem w niej jednoznacznej ceny lub wartosci potrzebnej do odpowiedzi.";
        }
        return "Odczytalem strone, ale nie otrzymalem tresci potrzebnej do przygotowania odpowiedzi.";
    }

    private Optional<SearchFallbackCandidate> firstPageLink(ToolResult toolResult) {
        Object links = toolResult.data().get("links");
        if (!(links instanceof List<?> list)) {
            return Optional.empty();
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String title = text(map.get("title"));
            String url = text(map.get("url"));
            if (!url.isBlank()) {
                return Optional.of(new SearchFallbackCandidate(title, "", url, ""));
            }
        }
        return Optional.empty();
    }

    private String fallbackWebSearchAnswer(ToolResult toolResult, boolean linkRequest) {
        Object results = toolResult.data().containsKey("acceptedResults")
                ? toolResult.data().get("acceptedResults")
                : toolResult.data().get("results");
        if (results instanceof List<?> list) {
            List<SearchFallbackCandidate> candidates = searchFallbackCandidates(list);
            if (linkRequest) {
                Optional<SearchFallbackCandidate> withUrl = candidates.stream()
                        .filter(candidate -> !candidate.url().isBlank())
                        .findFirst();
                if (withUrl.isPresent()) {
                    SearchFallbackCandidate candidate = withUrl.get();
                    return candidate.title().isBlank()
                            ? "Najbardziej pasujacy link: " + candidate.url()
                            : "Najbardziej pasujacy link: " + candidate.title() + " - " + candidate.url();
                }
            }
            long priced = candidates.stream().filter(candidate -> !candidate.price().isBlank()).count();
            for (SearchFallbackCandidate candidate : candidates) {
                if (!candidate.price().isBlank()) {
                    String prefix = priced > 0 && priced < 10
                            ? "Znalazlem " + priced + " wynikow z cena. "
                            : "";
                    return prefix + (candidate.title().isBlank()
                            ? "Znalazlem cene: " + candidate.price() + "."
                            : candidate.title() + ": " + candidate.price() + ".");
                }
            }
        }
        return "Znalazlem wyniki web, ale model nie zwrocil tresci finalnej odpowiedzi.";
    }

    private List<SearchFallbackCandidate> searchFallbackCandidates(List<?> list) {
        List<SearchFallbackCandidate> candidates = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String title = text(map.get("title"));
            String snippet = text(map.get("snippet"));
            String url = text(map.get("url"));
            candidates.add(new SearchFallbackCandidate(title, snippet, url, firstPrice(snippet)));
        }
        return candidates;
    }

    private boolean isLinkRequest(String message) {
        String normalized = normalizeAscii(message);
        return normalized.contains("link")
                || normalized.contains("url")
                || normalized.contains("adres")
                || normalized.contains("odnosnik")
                || normalized.contains("oferta")
                || normalized.contains("ogloszenie")
                || normalized.contains("listing")
                || normalized.contains("gdzie kupic")
                || normalized.contains("gdzie jest");
    }

    private String normalizeAscii(String value) {
        String normalized = Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('ł', 'l')
                .replace('Ł', 'L');
        return normalized.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String firstPrice(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        Matcher matcher = PRICE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group().trim() : "";
    }

    private boolean isTechnicalToolMessage(String message) {
        String normalized = message == null ? "" : message.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("web search finished")
                || normalized.equals("web page read finished")
                || normalized.equals("tool execution finished")
                || normalized.equals("websearchtool finished");
    }

    private String text(Object value) {
        return Objects.toString(value, "").trim();
    }

    private record SearchFallbackCandidate(String title, String snippet, String url, String price) {
    }

    private String toolBasePrompt(PipelineContext context) {
        StringBuilder builder = new StringBuilder();
        if (context.prompt() != null && !context.prompt().isBlank()) {
            builder.append(context.prompt());
            appendMainModelToolRequest(context, builder);
            return builder.toString();
        }
        builder.append("""
                You are J.A.R.V.I.S.

                Long-term memory policy:
                The Knowledge Workspace is the only authoritative long-term memory.
                Do not rely on legacy SQLite semantic memory.
                When asked to remember information permanently, use KnowledgeTool.

                """);
        if (!context.conversation().isEmpty()) {
            builder.append("""
                    === CONVERSATION CONTEXT ===

                    The following messages are recent working conversation context.
                    This is not durable long-term memory.
                    Use it only for continuity inside the current conversation.

                    ----------------------------------------

                    """);
            for (ConversationMessage message : context.conversation()) {
                builder.append(message.role().name())
                        .append(":\n")
                        .append(message.content())
                        .append("\n\n");
            }
            builder.append("=== END CONVERSATION CONTEXT ===\n\n");
        }
        builder.append("=== CURRENT USER MESSAGE ===\n\n")
                .append(context.request().message())
                .append("\n\n=== END CURRENT USER MESSAGE ===\n");
        String goal = String.valueOf(context.metadata().getOrDefault("toolGoal", ""));
        String reason = String.valueOf(context.metadata().getOrDefault("toolReason", ""));
        if (!goal.isBlank()) {
            appendMainModelToolRequest(goal, reason, builder);
        }
        return builder.toString();
    }

    private void appendMainModelToolRequest(PipelineContext context, StringBuilder builder) {
        String goal = String.valueOf(context.metadata().getOrDefault("toolGoal", ""));
        String reason = String.valueOf(context.metadata().getOrDefault("toolReason", ""));
        if (!goal.isBlank()) {
            appendMainModelToolRequest(goal, reason, builder);
        }
    }

    private void appendMainModelToolRequest(String goal, String reason, StringBuilder builder) {
        builder.append("\n=== MAIN MODEL TOOL REQUEST ===\n\n")
                .append("Goal:\n")
                .append(goal)
                .append("\n\nReason summary:\n")
                .append(reason)
                .append("\n\nThe main model requested an external capability. Now choose the concrete tool calls safely.\n")
                .append("=== END MAIN MODEL TOOL REQUEST ===\n");
    }

    private void publish(PipelineContext context, CognitiveEventType event, String status, String message, Map<String, Object> metadata) {
        context.cognitiveEventSink().accept(new CognitiveEvent(
                context.requestId(),
                context.conversationId(),
                Instant.now(),
                event,
                status,
                message,
                context.brain() == null ? null : context.brain().type(),
                context.model(),
                null,
                metadata
        ));
    }

    private AIProvider selectProvider(PipelineContext context) {
        return aiProviders.stream()
                .filter(provider -> provider.provider().equalsIgnoreCase(context.brain().provider()))
                .findFirst()
                .orElseThrow(() -> new AIProviderException("AI provider is not available: " + context.brain().provider()));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class ToolAnswerStreamState {
        private final StringBuilder raw = new StringBuilder();
        private final StringBuilder answer = new StringBuilder();
        private final StreamingStructuredResponseParser parser = new StreamingStructuredResponseParser();
        private GenerationFinishedEvent finishedEvent;
        private boolean modeDecided;
        private boolean structured;
        private boolean answerStarted;
        private int answerChunks;
        private long answerStartedNano;
    }

}
