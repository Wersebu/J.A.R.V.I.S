package com.jarvis.core.gmail;

/** A Gmail problem worth telling the user as is (not connected, no permission, message not found...). */
public class GmailException extends RuntimeException {

    public GmailException(String message) {
        super(message);
    }
}
