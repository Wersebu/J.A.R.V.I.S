package com.jarvis.memory.research;

import org.springframework.stereotype.Service;

/**
 * Validates whether research can produce a grounded final answer.
 */
@Service
public class ResearchCompletionValidator {

    /**
     * Returns whether final answering is allowed.
     *
     * @param context research context
     * @return true when answer can be grounded
     */
    public boolean canAnswer(ResearchContext context) {
        if (context.hasReadContent()) {
            return true;
        }
        return !looksLikeConcreteFactQuestion(context.originalQuery()) && !context.hasCandidates();
    }

    /**
     * Returns whether the question asks for a concrete value.
     *
     * @param query query
     * @return true for concrete facts
     */
    public boolean looksLikeConcreteFactQuestion(String query) {
        if (query == null) {
            return false;
        }
        String lower = query.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("what is")
                || lower.contains("what's")
                || lower.contains("jaki")
                || lower.contains("jaka")
                || lower.contains("jaką")
                || lower.contains("ile")
                || lower.contains("codename")
                || lower.contains("kodowa")
                || lower.contains("nazwa");
    }
}
