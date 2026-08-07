package com.jarvis.memory.pipeline;

import org.springframework.stereotype.Service;

/**
 * Integrated single-pass tool trigger strategy using the main model call.
 */
@Service
public class MainModelIntegratedToolTrigger implements ToolTriggerStrategy {

    @Override
    public String buildMainModelPrompt(PipelineContext context) {
        return context.prompt()
                + "\n\n=== TOOL TRIGGER POLICY ===\n\n"
                + "External capabilities are available for:\n"
                + "- reading and modifying persistent knowledge,\n"
                + "- accessing external systems,\n"
                + "- executing approved actions.\n\n"
                + "Return exactly one JSON object and no user-facing prose outside JSON.\n"
                + "Allowed types: FINAL_ANSWER, TOOL_REQUEST, CLARIFICATION.\n\n"
                + "Rules:\n"
                + "- If the request can be answered reliably from current conversation, supplied knowledge and your own reasoning, return FINAL_ANSWER.\n"
                + "- If fulfilling the request requires performing an action, modifying persistent state, reading data not already supplied, checking an external system or using an external capability, return TOOL_REQUEST.\n"
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
}
