package com.jarvis.memory.pipeline;

import com.jarvis.tools.runtime.ToolIntent;
import com.jarvis.tools.runtime.ToolIntentDetector;
import com.jarvis.tools.runtime.InformationFreshness;
import com.jarvis.tools.runtime.InformationFreshnessEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Integrated single-pass tool trigger strategy using the main model call.
 */
@Service
public class MainModelIntegratedToolTrigger implements ToolTriggerStrategy {

    private final ToolIntentDetector toolIntentDetector;
    private final InformationFreshnessEvaluator freshnessEvaluator;

    /**
     * Creates the integrated tool trigger strategy.
     *
     * @param toolIntentDetector lightweight advisory detector
     */
    public MainModelIntegratedToolTrigger(ToolIntentDetector toolIntentDetector) {
        this(toolIntentDetector, new InformationFreshnessEvaluator());
    }

    /**
     * Creates the integrated tool trigger strategy.
     *
     * @param toolIntentDetector lightweight advisory detector
     * @param freshnessEvaluator request freshness classifier
     */
    @Autowired
    public MainModelIntegratedToolTrigger(ToolIntentDetector toolIntentDetector, InformationFreshnessEvaluator freshnessEvaluator) {
        this.toolIntentDetector = toolIntentDetector;
        this.freshnessEvaluator = freshnessEvaluator;
    }

    @Override
    public String buildMainModelPrompt(PipelineContext context) {
        ToolIntent detectedIntent = toolIntentDetector.detect(context.request().message());
        InformationFreshness freshness = freshnessEvaluator.evaluate(context.request().message(), "", "");
        return context.prompt()
                + attachmentPolicy(context)
                + "\n\n=== TOOL TRIGGER POLICY ===\n\n"
                + "External capabilities are available for:\n"
                + "- reading and modifying persistent knowledge,\n"
                + "- accessing external systems,\n"
                + "- searching the live public web through local SearXNG,\n"
                + "- executing approved actions.\n\n"
                + "Return exactly one JSON object and no user-facing prose outside JSON.\n"
                + "Allowed types: FINAL_ANSWER, TOOL_REQUEST, CLARIFICATION.\n\n"
                + "Core advisory signal: " + detectedIntent + "\n"
                + "Required information freshness: " + freshness + "\n"
                + advisoryRule(detectedIntent)
                + "\n"
                + "Rules:\n"
                + "- If the request can be answered reliably from current conversation, supplied knowledge and your own reasoning, return FINAL_ANSWER.\n"
                + "- If fulfilling the request requires performing an action, modifying persistent state, reading data not already supplied, checking an external system, checking current prices/rates/market data, or using an external capability, return TOOL_REQUEST.\n"
                + "- Current prices, used-market prices, exchange rates, commodity prices, gold prices, news, releases, and online facts are not available from memory alone. Return TOOL_REQUEST for them.\n"
                + "- If Required information freshness is MUST_BE_LIVE, your training knowledge may be stale. Absence from your memory is NOT evidence that an entity does not exist.\n"
                + "- For MUST_BE_LIVE, do not return FINAL_ANSWER about the current world unless current evidence is already supplied in the prompt.\n"
                + "- Do not answer that permissions/tools are unavailable when the policy says an external capability is needed. Return TOOL_REQUEST and let Core execute the tool.\n"
                + "- If required information is missing and a safe action cannot yet be chosen, return CLARIFICATION.\n"
                + "- Never pretend that a tool was used.\n"
                + "- Never guess a tool result.\n"
                + "- Do not select the concrete tool at this stage.\n"
                + "- Keep \"goal\" and \"reason\" short and never put full document bodies, source code, or other long "
                + "content to be WRITTEN into this JSON - the concrete tool call that actually writes it happens in a "
                + "separate later step, where that content is a normal tool-call argument, not part of this envelope. "
                + "Cramming long written content into \"goal\"/\"reason\" here often breaks JSON escaping and wastes "
                + "an entire turn.\n"
                + "- This does not apply to short factual data you already extracted (e.g. from an attached image) "
                + "and that the next tool call will need as INPUT, such as a list of addresses, names, or numbers - "
                + "include that concrete data directly in \"goal\" so the tool selection has enough information to "
                + "work with, instead of a vague goal like \"use a tool\" or \"geocode the addresses in the image\".\n\n"
                + "Schemas:\n"
                + "{\"type\":\"FINAL_ANSWER\",\"answer\":\"...\"}\n"
                + "{\"type\":\"TOOL_REQUEST\",\"goal\":\"short task description, e.g. 'Create a knowledge document "
                + "about X'\",\"reason\":\"short reason\",\"context\":{\"importantEntities\":[]}}\n"
                + "{\"type\":\"CLARIFICATION\",\"question\":\"...\"}\n\n"
                + "=== END TOOL TRIGGER POLICY ===\n";
    }

    /**
     * Builds the current-message-attachments policy section, only when the request actually has
     * images - keeps the prompt unchanged for plain-text requests.
     *
     * @param context pipeline context
     * @return attachment policy section, or an empty string when there are no images
     */
    private String attachmentPolicy(PipelineContext context) {
        if (context.images().isEmpty()) {
            return "";
        }
        return "\n\n=== CURRENT MESSAGE ATTACHMENTS ===\n\n"
                + "Images attached to the current user message are already directly visible to you in this "
                + "same response, through your own multimodal vision - they are NOT Knowledge Workspace "
                + "documents and were never saved anywhere.\n"
                + "- Do not request a tool to retrieve, load, search for, download, or analyze an image that is "
                + "already attached to the current user message. No tool has access to it; only you do, right now.\n"
                + "- Do not use KnowledgeTool to locate a current-message attachment - KnowledgeTool only searches "
                + "persisted documents in the Knowledge Workspace, a completely different, separate store.\n"
                + "- When information required for a tool call is visible in an attached image (addresses, names, "
                + "numbers, table contents, ...), first read and extract that information yourself using your own "
                + "vision, in this response. Then request only the external operation that must actually be "
                + "performed on that extracted data (e.g. geocoding a list of addresses you already read), with "
                + "the extracted data included in \"goal\" as described below.\n"
                + "- KnowledgeTool remains correct whenever the user is actually asking about persisted knowledge "
                + "(e.g. \"sprawdz w zapisanej wiedzy...\") - this section only concerns the images attached to "
                + "THIS message, not the separate Knowledge Workspace.\n"
                + "=== END CURRENT MESSAGE ATTACHMENTS ===\n";
    }

    private String advisoryRule(ToolIntent detectedIntent) {
        if (detectedIntent == ToolIntent.SEARCH_WEB) {
            return "This request may require live external/web data. You own the final decision: return TOOL_REQUEST only when live data is truly needed and is not already supplied in the prompt.";
        }
        if (detectedIntent == ToolIntent.NO_TOOL) {
            return "No tool-specific signal was detected. You still own the final decision and may request a tool if the task requires one.";
        }
        return "A native tool may be relevant. You own the final decision: return TOOL_REQUEST only when the operation is needed to satisfy the request.";
    }
}
