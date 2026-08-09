package com.jarvis.memory.pipeline;

import com.jarvis.tools.runtime.ToolIntent;
import com.jarvis.tools.runtime.ToolIntentDetector;
import org.springframework.stereotype.Service;

/**
 * Integrated single-pass tool trigger strategy using the main model call.
 */
@Service
public class MainModelIntegratedToolTrigger implements ToolTriggerStrategy {

    private final ToolIntentDetector toolIntentDetector;

    /**
     * Creates the integrated tool trigger strategy.
     *
     * @param toolIntentDetector lightweight advisory detector
     */
    public MainModelIntegratedToolTrigger(ToolIntentDetector toolIntentDetector) {
        this.toolIntentDetector = toolIntentDetector;
    }

    @Override
    public String buildMainModelPrompt(PipelineContext context) {
        ToolIntent detectedIntent = toolIntentDetector.detect(context.request().message());
        return context.prompt()
                + "\n\n=== TOOL TRIGGER POLICY ===\n\n"
                + "External capabilities are available for:\n"
                + "- reading and modifying persistent knowledge,\n"
                + "- accessing external systems,\n"
                + "- searching the live public web through local SearXNG,\n"
                + "- executing approved actions.\n\n"
                + "Return exactly one JSON object and no user-facing prose outside JSON.\n"
                + "Allowed types: FINAL_ANSWER, TOOL_REQUEST, CLARIFICATION.\n\n"
                + "Advisory detected tool intent from Core: " + detectedIntent + "\n"
                + advisoryRule(detectedIntent)
                + "\n"
                + "Rules:\n"
                + "- If the request can be answered reliably from current conversation, supplied knowledge and your own reasoning, return FINAL_ANSWER.\n"
                + "- If fulfilling the request requires performing an action, modifying persistent state, reading data not already supplied, checking an external system, checking current prices/rates/market data, or using an external capability, return TOOL_REQUEST.\n"
                + "- Current prices, used-market prices, exchange rates, commodity prices, gold prices, news, releases, and online facts are not available from memory alone. Return TOOL_REQUEST for them.\n"
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
            return "The user request appears to require live external/web data. You MUST return TOOL_REQUEST unless the live result is already explicitly supplied in the prompt.";
        }
        if (detectedIntent == ToolIntent.NO_TOOL) {
            return "No tool-specific intent was detected. Use your judgment with the rules below.";
        }
        return "The user request appears to involve a native tool. Prefer TOOL_REQUEST when the operation is needed to satisfy the request.";
    }
}
