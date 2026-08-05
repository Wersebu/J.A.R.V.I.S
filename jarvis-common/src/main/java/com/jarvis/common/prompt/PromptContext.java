package com.jarvis.common.prompt;

import java.util.List;

/**
 * Source-aware prompt context.
 *
 * @param hasMemory whether memory evidence is available
 * @param hasKnowledge whether knowledge evidence is available
 * @param hasConversationEvidence whether conversation evidence is available
 * @param hasToolEvidence whether tool evidence is available
 * @param groundingSources available grounding sources
 * @param personalQueryAnalysis personal query analysis
 * @param responseMode selected response mode
 */
public record PromptContext(
        boolean hasMemory,
        boolean hasKnowledge,
        boolean hasConversationEvidence,
        boolean hasToolEvidence,
        List<GroundingSource> groundingSources,
        PersonalQueryAnalysis personalQueryAnalysis,
        ResponseMode responseMode
) {
    /**
     * Creates an empty standard prompt context.
     *
     * @return empty prompt context
     */
    public static PromptContext empty() {
        return new PromptContext(false, false, false, false, List.of(), PersonalQueryAnalysis.none(), ResponseMode.STANDARD);
    }

    /**
     * Creates a normalized prompt context.
     *
     * @param groundingSources available grounding sources
     * @param personalQueryAnalysis personal query analysis
     */
    public PromptContext {
        groundingSources = groundingSources == null ? List.of() : List.copyOf(groundingSources);
        personalQueryAnalysis = personalQueryAnalysis == null ? PersonalQueryAnalysis.none() : personalQueryAnalysis;
        responseMode = responseMode == null ? ResponseMode.STANDARD : responseMode;
    }
}
