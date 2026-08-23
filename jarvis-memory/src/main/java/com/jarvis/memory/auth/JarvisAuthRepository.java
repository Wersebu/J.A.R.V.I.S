package com.jarvis.memory.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JarvisAuthRepository {

    boolean hasUsers();

    UserAccount createUser(String email, String passwordHash, String displayName, String role, Instant now);

    Optional<UserAccount> findUserByEmail(String email);

    Optional<UserAccount> findUserById(String userId);

    Optional<String> passwordHash(String userId);

    void updateLastLogin(String userId, Instant now);

    String createSession(String userId, String tokenHash, Instant now, Instant expiresAt);

    Optional<UserAccount> findUserBySessionTokenHash(String tokenHash, Instant now);

    void deleteSession(String tokenHash);

    UserSettings settings(String userId);

    UserSettings saveSettings(String userId, String globalPrompt, Instant now);

    List<ConversationFolder> folders(String userId);

    ConversationFolder createFolder(String userId, String name, String systemPrompt, Instant now);

    Optional<ConversationFolder> folder(String userId, String folderId);

    ConversationFolder updateFolder(String userId, String folderId, String name, String systemPrompt, Instant now);

    void deleteFolder(String userId, String folderId);
}
