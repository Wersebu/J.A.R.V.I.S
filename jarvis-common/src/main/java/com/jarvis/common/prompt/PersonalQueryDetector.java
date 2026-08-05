package com.jarvis.common.prompt;

/**
 * Detects whether a user request asks about personal user data.
 */
public interface PersonalQueryDetector {

    /**
     * Analyzes a user message.
     *
     * @param message user message
     * @return personal query analysis
     */
    PersonalQueryAnalysis analyze(String message);
}
