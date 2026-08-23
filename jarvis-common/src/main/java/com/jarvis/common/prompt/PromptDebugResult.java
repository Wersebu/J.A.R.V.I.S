package com.jarvis.common.prompt;

/**
 * Debug view of a prompt before it is sent to an AI provider.
 *
 * @param systemPrompt system portion of the prompt
 * @param knowledge injected knowledge portion of the prompt
 * @param userPrompt user portion of the prompt
 * @param finalPrompt complete prompt
 */
public record PromptDebugResult(
        String systemPrompt,
        String userGlobalPrompt,
        String folderSystemPrompt,
        String knowledge,
        String userPrompt,
        String finalPrompt
) {
    public PromptDebugResult(String systemPrompt, String knowledge, String userPrompt, String finalPrompt) {
        this(systemPrompt, "", "", knowledge, userPrompt, finalPrompt);
    }
}
