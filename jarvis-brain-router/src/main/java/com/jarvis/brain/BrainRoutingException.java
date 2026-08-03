package com.jarvis.brain;

import com.jarvis.common.ai.AIProviderException;

/**
 * Runtime exception for brain routing failures.
 */
public class BrainRoutingException extends AIProviderException {

    /**
     * Creates a brain routing exception.
     *
     * @param message failure message
     */
    public BrainRoutingException(String message) {
        super(message);
    }
}
