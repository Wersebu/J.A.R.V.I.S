package com.jarvis.memory.auth;

import com.jarvis.memory.cognitive.MemoryProperties;
import com.jarvis.memory.sqlite.SQLiteConnectionFactory;
import com.jarvis.memory.sqlite.SQLiteMemoryInitializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JarvisAuthServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void bootstrapLoginBearerAuthenticationAndLogoutUseHashedSessions() {
        SQLiteConnectionFactory connectionFactory = new SQLiteConnectionFactory(
                new MemoryProperties(tempDir.resolve("auth-test.db").toString(), 3, null, null, null, null));
        new SQLiteMemoryInitializer(connectionFactory).afterPropertiesSet();
        SQLiteJarvisAuthRepository repository = new SQLiteJarvisAuthRepository(connectionFactory);
        JarvisAuthService service = new JarvisAuthService(repository);

        assertThat(service.bootstrapRequired()).isTrue();
        AuthenticatedSession bootstrap = service.bootstrap("Admin@Example.com", "very-secret", "Admin");

        assertThat(service.bootstrapRequired()).isFalse();
        assertThat(bootstrap.user().email()).isEqualTo("admin@example.com");
        assertThat(repository.passwordHash(bootstrap.user().id()).orElseThrow()).doesNotContain("very-secret");
        assertThat(service.authenticateBearer("Bearer " + bootstrap.token())).get().extracting(UserAccount::email)
                .isEqualTo("admin@example.com");

        assertThatThrownBy(() -> service.login("admin@example.com", "wrong-secret"))
                .isInstanceOf(IllegalArgumentException.class);

        AuthenticatedSession login = service.login("admin@example.com", "very-secret");
        assertThat(service.authenticateBearer("Bearer " + login.token())).isPresent();

        service.logout("Bearer " + login.token());
        assertThat(service.authenticateBearer("Bearer " + login.token())).isEmpty();
    }
}
