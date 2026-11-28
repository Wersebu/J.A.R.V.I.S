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

    public java.util.Set<String> requiredScopes(String operation){
        String op=operation==null?"":operation.toUpperCase(java.util.Locale.ROOT);
        if(java.util.Set.of("STATUS","DISCONNECT").contains(op))return java.util.Set.of();
        if(this==GMAIL)return java.util.Set.of(op.equals("CREATE_DRAFT")||op.equals("SEND_DRAFT")?"https://www.googleapis.com/auth/gmail.compose":"https://www.googleapis.com/auth/gmail.readonly");
        if(java.util.Set.of("TASKS","ADD_TASK","COMPLETE_TASK","EXPORT_SCHEDULE").contains(op))return java.util.Set.of("https://www.googleapis.com/auth/tasks"+(op.equals("TASKS")?".readonly":""));
        return java.util.Set.of("https://www.googleapis.com/auth/"+(java.util.Set.of("CREATE","MOVE").contains(op)?"calendar.events":"calendar.readonly"));
    }
    public static boolean permits(java.util.Set<String> granted,String required){
        if(granted.contains(required))return true;
        String base="https://www.googleapis.com/auth/";
        if(required.equals(base+"gmail.readonly"))return granted.contains(base+"gmail.modify")||granted.contains("https://mail.google.com/");
        if(required.equals(base+"gmail.compose"))return granted.contains(base+"gmail.modify")||granted.contains("https://mail.google.com/");
        if(required.startsWith(base+"calendar"))return granted.contains(base+"calendar");
        if(required.equals(base+"tasks.readonly"))return granted.contains(base+"tasks");
        if(required.equals(base+"drive.readonly"))return granted.contains(base+"drive");
        return false;
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
