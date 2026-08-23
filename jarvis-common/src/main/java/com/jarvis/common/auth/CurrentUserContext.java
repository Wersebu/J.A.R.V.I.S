package com.jarvis.common.auth;

import java.util.Optional;

/**
 * Per-request authenticated user context shared by API and persistence layers.
 */
public final class CurrentUserContext {

    public static final String LOCAL_USER_ID = "local-user";

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();

    private CurrentUserContext() {
    }

    public static void set(String userId) {
        USER_ID.set(normalize(userId));
    }

    public static Optional<String> currentUserId() {
        String value = USER_ID.get();
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    public static String requiredUserId() {
        return currentUserId().orElse(LOCAL_USER_ID);
    }

    public static void clear() {
        USER_ID.remove();
    }

    public static void runAs(String userId, Runnable runnable) {
        String previous = USER_ID.get();
        set(userId);
        try {
            runnable.run();
        } finally {
            if (previous == null) {
                clear();
            } else {
                USER_ID.set(previous);
            }
        }
    }

    private static String normalize(String userId) {
        return userId == null || userId.isBlank() ? LOCAL_USER_ID : userId;
    }
}
