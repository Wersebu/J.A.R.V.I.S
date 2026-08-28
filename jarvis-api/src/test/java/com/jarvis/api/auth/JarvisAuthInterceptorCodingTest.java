package com.jarvis.api.auth;

import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.memory.auth.ConversationFolder;
import com.jarvis.memory.auth.JarvisAuthRepository;
import com.jarvis.memory.auth.JarvisAuthService;
import com.jarvis.memory.auth.UserAccount;
import com.jarvis.memory.auth.UserSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JarvisAuthInterceptorCodingTest {

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
    }

    @Test
    void codingApiRequiresBearerAuthentication() throws Exception {
        JarvisAuthInterceptor interceptor = new JarvisAuthInterceptor(new JarvisAuthService(new FakeAuthRepository()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/coding/workspaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Authentication required.");
    }

    @Test
    void codingApiAcceptsTheSameBearerAuthenticationAsOtherApiRoutes() throws Exception {
        FakeAuthRepository repository = new FakeAuthRepository();
        JarvisAuthService authService = new JarvisAuthService(repository);
        String tokenHash = authService.tokenHash("valid-token");
        repository.validTokenHash = tokenHash;
        JarvisAuthInterceptor interceptor = new JarvisAuthInterceptor(authService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/coding/workspaces");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(CurrentUserContext.currentUserId()).contains("user-1");
    }

    private static final class FakeAuthRepository implements JarvisAuthRepository {
        private final UserAccount user = new UserAccount("user-1", "user@example.com", "User", "USER", true, Instant.now(), Instant.now());
        private String validTokenHash = "";

        @Override
        public boolean hasUsers() {
            return true;
        }

        @Override
        public UserAccount createUser(String email, String passwordHash, String displayName, String role, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UserAccount> findUserByEmail(String email) {
            return Optional.empty();
        }

        @Override
        public Optional<UserAccount> findUserById(String userId) {
            return Optional.of(user);
        }

        @Override
        public Optional<String> passwordHash(String userId) {
            return Optional.empty();
        }

        @Override
        public void updateLastLogin(String userId, Instant now) {
        }

        @Override
        public String createSession(String userId, String tokenHash, Instant now, Instant expiresAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UserAccount> findUserBySessionTokenHash(String tokenHash, Instant now) {
            return validTokenHash.equals(tokenHash) ? Optional.of(user) : Optional.empty();
        }

        @Override
        public void deleteSession(String tokenHash) {
        }

        @Override
        public UserSettings settings(String userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserSettings saveSettings(String userId, String globalPrompt, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<ConversationFolder> folders(String userId) {
            return List.of();
        }

        @Override
        public ConversationFolder createFolder(String userId, String name, String systemPrompt, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ConversationFolder> folder(String userId, String folderId) {
            return Optional.empty();
        }

        @Override
        public ConversationFolder updateFolder(String userId, String folderId, String name, String systemPrompt, Instant now) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteFolder(String userId, String folderId) {
        }
    }
}
