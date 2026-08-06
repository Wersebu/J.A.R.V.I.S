package com.jarvis.common.prompt;

/**
 * Manages editable J.A.R.V.I.S. system instructions.
 */
public interface SystemPromptService {

    /**
     * Loads current system instructions.
     *
     * @return instructions text
     */
    String load();

    /**
     * Saves system instructions.
     *
     * @param instructions instructions text
     * @return saved instructions
     */
    String save(String instructions);
}
