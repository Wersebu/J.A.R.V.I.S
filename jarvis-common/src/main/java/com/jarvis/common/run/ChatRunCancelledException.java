package com.jarvis.common.run;

/**
 * Thrown inside a chat run after the user pressed Stop; unwinds the pipeline.
 */
public class ChatRunCancelledException extends RuntimeException {

    /**
     * Creates the exception.
     */
    public ChatRunCancelledException() {
        super("Stopped by the user");
    }
}
