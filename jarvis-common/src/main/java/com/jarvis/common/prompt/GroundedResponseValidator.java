package com.jarvis.common.prompt;

/**
 * Validates whether a response is grounded in supplied personal sources.
 */
public interface GroundedResponseValidator {

    /**
     * Validates a generated response.
     *
     * @param response generated response
     * @param promptContext source-aware prompt context
     * @return validation result
     */
    GroundedValidationResult validate(String response, PromptContext promptContext);
}
