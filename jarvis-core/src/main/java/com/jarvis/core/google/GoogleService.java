package com.jarvis.core.google;

import java.util.List;

/**
 * Google services a Jarvis user can connect. Each one is its own consent and its own encrypted token, so the user
 * grants exactly what a feature needs and can disconnect one without touching the other.
 */
public enum GoogleService {

    /** Read and compose mail, with sending only after an explicit UI approval. */
    GMAIL("gmail", "Gmail", List.of(
            "https://www.googleapis.com/auth/gmail.readonly",
            "https://www.googleapis.com/auth/gmail.compose")),
    /** Calendars (read), events (read, add, move) and tasks (read, add, complete). Deleting is not offered by Jarvis. */
    CALENDAR("calendar", "Google Calendar", List.of(
            "https://www.googleapis.com/auth/calendar.readonly",
            "https://www.googleapis.com/auth/calendar.events",
            "https://www.googleapis.com/auth/tasks"));

    private final String provider;
    private final String label;
    private final List<String> scopes;

    GoogleService(String provider, String label, List<String> scopes) {
        this.provider = provider;
        this.label = label;
        this.scopes = scopes;
    }

    /** Key of the stored connection. */
    public String provider() {
        return provider;
    }

    /** Name used in messages. */
    public String label() {
        return label;
    }

    public List<String> scopes() {
        return scopes;
    }
}
