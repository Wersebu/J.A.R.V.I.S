package com.jarvis.knowledge.vault.embedding;

import java.util.List;
import java.util.OptionalInt;

/**
 * Transport to one embedding model.
 */
public interface EmbeddingBackend {

    /**
     * Returns a short provider/model description.
     *
     * @return description such as {@code ollama:bge-m3}
     */
    String describe();

    /**
     * Embeds inputs exactly as given (prefixes are already applied).
     *
     * @param inputs texts
     * @return one vector per input, same order
     * @throws EmbeddingException when the provider is unreachable or rejects the request
     */
    List<float[]> embed(List<String> inputs);

    /**
     * Counts tokens with the provider's real tokenizer when it has one.
     *
     * @param text text
     * @return token count, or empty when the provider has no tokenizer endpoint
     */
    default OptionalInt countTokens(String text) {
        return OptionalInt.empty();
    }

    /**
     * Embedding failure.
     */
    class EmbeddingException extends RuntimeException {
        /**
         * Creates the exception.
         *
         * @param message message
         * @param cause cause
         */
        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }

        /**
         * Creates the exception.
         *
         * @param message message
         */
        public EmbeddingException(String message) {
            super(message);
        }
    }

    /**
     * The provider rejected an input as longer than the model limit (it was not truncated).
     */
    class InputTooLongException extends EmbeddingException {
        /**
         * Creates the exception.
         *
         * @param message message
         */
        public InputTooLongException(String message) {
            super(message);
        }
    }
}
