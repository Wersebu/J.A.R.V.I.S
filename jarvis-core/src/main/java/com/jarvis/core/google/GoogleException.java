package com.jarvis.core.google;

/** A Google account problem worth telling the user as is (not connected, no permission, message not found...). */
public class GoogleException extends RuntimeException {

    public GoogleException(String message) {
        super(message);
    }
}
