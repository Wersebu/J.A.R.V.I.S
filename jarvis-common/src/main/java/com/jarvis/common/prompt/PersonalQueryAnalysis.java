package com.jarvis.common.prompt;

/**
 * Result of personal query detection.
 *
 * @param personalQuery whether the request asks about the user
 * @param personalTopic detected personal topic
 * @param confidence deterministic confidence
 */
public record PersonalQueryAnalysis(
        boolean personalQuery,
        PersonalTopic personalTopic,
        double confidence
) {
    /**
     * Creates a non-personal analysis.
     *
     * @return non-personal analysis
     */
    public static PersonalQueryAnalysis none() {
        return new PersonalQueryAnalysis(false, PersonalTopic.OTHER, 0.0);
    }
}
