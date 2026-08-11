package com.jarvis.memory.pipeline;

import com.jarvis.tools.runtime.ToolIntent;
import com.jarvis.tools.runtime.ToolIntentDetector;
import com.jarvis.tools.runtime.InformationFreshness;
import com.jarvis.tools.runtime.InformationFreshnessEvaluator;
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
    public MainModelIntegratedToolTrigger(ToolIntentDetector toolIntentDetector, InformationFreshnessEvaluator freshnessEvaluator) {
        this.toolIntentDetector = toolIntentDetector;
        this.freshnessEvaluator = freshnessEvaluator;
    }

    @Override
    public String buildMainModelPrompt(PipelineContext context) {
        ToolIntent detectedIntent = toolIntentDetector.detect(context.request().message());
        InformationFreshness freshness = freshnessEvaluator.evaluate(context.request().message(), "", "");
        return context.prompt()
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
                + "- Do not select the concrete tool at this stage.\n\n"
                + "Schemas:\n"
                + "{\"type\":\"FINAL_ANSWER\",\"answer\":\"...\"}\n"
                + "{\"type\":\"TOOL_REQUEST\",\"goal\":\"...\",\"reason\":\"...\",\"context\":{\"importantEntities\":[]}}\n"
                + "{\"type\":\"CLARIFICATION\",\"question\":\"...\"}\n\n"
                + "=== END TOOL TRIGGER POLICY ===\n";
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
