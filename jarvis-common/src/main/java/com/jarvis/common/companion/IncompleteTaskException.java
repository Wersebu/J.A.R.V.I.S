package com.jarvis.common.companion;

/** Carries usable partial work without misclassifying an incomplete run as success. */
public class IncompleteTaskException extends RuntimeException {
    private final String partialResult;
    public IncompleteTaskException(String reason,String partialResult){super(reason);this.partialResult=partialResult;}
    public String partialResult(){return partialResult;}
}
