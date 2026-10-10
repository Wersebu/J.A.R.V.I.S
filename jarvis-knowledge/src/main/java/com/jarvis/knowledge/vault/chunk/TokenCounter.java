package com.jarvis.knowledge.vault.chunk;

/**
 * Counts model tokens for a text.
 */
@FunctionalInterface
public interface TokenCounter {

    /**
     * Counts tokens.
     *
     * @param text text
     * @return token count including model special tokens
     */
    int count(String text);
}
