package com.jarvis.memory.auth;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class JarvisAuthService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration SESSION_TTL = Duration.ofDays(14);

    private final JarvisAuthRepository repository;

    public JarvisAuthService(JarvisAuthRepository repository) {
        this.repository = repository;
    }

    public boolean bootstrapRequired() {
        return !repository.hasUsers();
    }

    public AuthenticatedSession bootstrap(String email, String password, String displayName) {
        if (repository.hasUsers()) {
            throw new IllegalStateException("Bootstrap is already complete.");
        }
        validateCredentials(email, password);
        UserAccount user = repository.createUser(email, PasswordHasher.hash(password), displayName, "ADMIN", Instant.now());
        return issueSession(user);
    }

    public AuthenticatedSession login(String email, String password) {
        UserAccount user = repository.findUserByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password."));
        String hash = repository.passwordHash(user.id())
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password."));
        if (!PasswordHasher.matches(password, hash)) {
            throw new IllegalArgumentException("Invalid email or password.");
        }
        repository.updateLastLogin(user.id(), Instant.now());
        return issueSession(user);
    }

    public Optional<UserAccount> authenticateBearer(String header) {
        String token = bearer(header);
        if (token.isBlank()) {
            return Optional.empty();
        }
        return repository.findUserBySessionTokenHash(tokenHash(token), Instant.now());
    }

    public void logout(String header) {
        String token = bearer(header);
        if (!token.isBlank()) {
            repository.deleteSession(tokenHash(token));
        }
    }

    public String tokenHash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash session token", exception);
        }
    }

    private AuthenticatedSession issueSession(UserAccount user) {
        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(SESSION_TTL);
        String sessionId = repository.createSession(user.id(), tokenHash(token), now, expiresAt);
        return new AuthenticatedSession(token, sessionId, user, expiresAt);
    }

    private String bearer(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return "";
        }
        return header.substring(7).strip();
    }

    private void validateCredentials(String email, String password) {
        if (email == null || !email.contains("@")) {
            throw new IllegalArgumentException("Valid email is required.");
        }
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must contain at least 8 characters.");
        }
    }
}
