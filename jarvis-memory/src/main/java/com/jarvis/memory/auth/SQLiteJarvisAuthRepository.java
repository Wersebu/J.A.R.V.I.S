package com.jarvis.memory.auth;

import com.jarvis.memory.sqlite.SQLiteConnectionFactory;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SQLiteJarvisAuthRepository implements JarvisAuthRepository {

    private final SQLiteConnectionFactory connectionFactory;

    public SQLiteJarvisAuthRepository(SQLiteConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public boolean hasUsers() {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM users")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not count users", exception);
        }
    }

    @Override
    public UserAccount createUser(String email, String passwordHash, String displayName, String role, Instant now) {
        String id = UUID.randomUUID().toString();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO users (id, email, password_hash, display_name, role, active, created_at, updated_at, last_login_at)
                     VALUES (?, ?, ?, ?, ?, 1, ?, ?, '')
                     """)) {
            statement.setString(1, id);
            statement.setString(2, normalizeEmail(email));
            statement.setString(3, passwordHash);
            statement.setString(4, displayName == null || displayName.isBlank() ? normalizeEmail(email) : displayName.strip());
            statement.setString(5, role == null || role.isBlank() ? "USER" : role);
            statement.setString(6, now.toString());
            statement.setString(7, now.toString());
            statement.executeUpdate();
            saveSettings(id, "", now);
            return findUserById(id).orElseThrow();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create user", exception);
        }
    }

    @Override
    public Optional<UserAccount> findUserByEmail(String email) {
        return findUser("SELECT * FROM users WHERE email = ?", normalizeEmail(email));
    }

    @Override
    public Optional<UserAccount> findUserById(String userId) {
        return findUser("SELECT * FROM users WHERE id = ?", userId);
    }

    @Override
    public Optional<String> passwordHash(String userId) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT password_hash FROM users WHERE id = ? AND active = 1")) {
            statement.setString(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.ofNullable(resultSet.getString("password_hash")) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read password hash", exception);
        }
    }

    @Override
    public void updateLastLogin(String userId, Instant now) {
        execute("UPDATE users SET last_login_at = ?, updated_at = ? WHERE id = ?", now.toString(), now.toString(), userId);
    }

    @Override
    public String createSession(String userId, String tokenHash, Instant now, Instant expiresAt) {
        String id = UUID.randomUUID().toString();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO user_sessions (id, user_id, token_hash, created_at, expires_at, revoked_at)
                     VALUES (?, ?, ?, ?, ?, '')
                     """)) {
            statement.setString(1, id);
            statement.setString(2, userId);
            statement.setString(3, tokenHash);
            statement.setString(4, now.toString());
            statement.setString(5, expiresAt.toString());
            statement.executeUpdate();
            return id;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create session", exception);
        }
    }

    @Override
    public Optional<UserAccount> findUserBySessionTokenHash(String tokenHash, Instant now) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT u.* FROM user_sessions s
                     JOIN users u ON u.id = s.user_id
                     WHERE s.token_hash = ? AND s.revoked_at = '' AND s.expires_at > ? AND u.active = 1
                     """)) {
            statement.setString(1, tokenHash);
            statement.setString(2, now.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapUser(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read session", exception);
        }
    }

    @Override
    public void deleteSession(String tokenHash) {
        execute("UPDATE user_sessions SET revoked_at = ? WHERE token_hash = ?", Instant.now().toString(), tokenHash);
    }

    @Override
    public UserSettings settings(String userId) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM user_settings WHERE user_id = ?")) {
            statement.setString(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new UserSettings(userId, resultSet.getString("global_prompt"), parseInstant(resultSet.getString("updated_at")));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read settings", exception);
        }
        return saveSettings(userId, "", Instant.now());
    }

    @Override
    public UserSettings saveSettings(String userId, String globalPrompt, Instant now) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO user_settings (user_id, global_prompt, updated_at)
                     VALUES (?, ?, ?)
                     ON CONFLICT(user_id) DO UPDATE SET global_prompt = excluded.global_prompt, updated_at = excluded.updated_at
                     """)) {
            statement.setString(1, userId);
            statement.setString(2, globalPrompt == null ? "" : globalPrompt);
            statement.setString(3, now.toString());
            statement.executeUpdate();
            return new UserSettings(userId, globalPrompt == null ? "" : globalPrompt, now);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save settings", exception);
        }
    }

    @Override
    public List<ConversationFolder> folders(String userId) {
        List<ConversationFolder> folders = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT * FROM conversation_folders WHERE user_id = ? ORDER BY lower(name) ASC
                     """)) {
            statement.setString(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    folders.add(mapFolder(resultSet));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not list folders", exception);
        }
        return folders;
    }

    @Override
    public ConversationFolder createFolder(String userId, String name, String systemPrompt, Instant now) {
        String id = UUID.randomUUID().toString();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO conversation_folders (id, user_id, name, system_prompt, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            statement.setString(1, id);
            statement.setString(2, userId);
            statement.setString(3, normalizeName(name));
            statement.setString(4, systemPrompt == null ? "" : systemPrompt);
            statement.setString(5, now.toString());
            statement.setString(6, now.toString());
            statement.executeUpdate();
            return folder(userId, id).orElseThrow();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create folder", exception);
        }
    }

    @Override
    public Optional<ConversationFolder> folder(String userId, String folderId) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM conversation_folders WHERE user_id = ? AND id = ?")) {
            statement.setString(1, userId);
            statement.setString(2, folderId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapFolder(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read folder", exception);
        }
    }

    @Override
    public ConversationFolder updateFolder(String userId, String folderId, String name, String systemPrompt, Instant now) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE conversation_folders
                     SET name = COALESCE(?, name), system_prompt = COALESCE(?, system_prompt), updated_at = ?
                     WHERE user_id = ? AND id = ?
                     """)) {
            statement.setString(1, name == null ? null : normalizeName(name));
            statement.setString(2, systemPrompt);
            statement.setString(3, now.toString());
            statement.setString(4, userId);
            statement.setString(5, folderId);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("Folder not found: " + folderId);
            }
            return folder(userId, folderId).orElseThrow();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not update folder", exception);
        }
    }

    @Override
    public void deleteFolder(String userId, String folderId) {
        try (Connection connection = connectionFactory.openConnection()) {
            try (PreparedStatement clear = connection.prepareStatement("UPDATE conversations SET folder_id = '' WHERE user_id = ? AND folder_id = ?")) {
                clear.setString(1, userId);
                clear.setString(2, folderId);
                clear.executeUpdate();
            }
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM conversation_folders WHERE user_id = ? AND id = ?")) {
                delete.setString(1, userId);
                delete.setString(2, folderId);
                delete.executeUpdate();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not delete folder", exception);
        }
    }

    private Optional<UserAccount> findUser(String sql, String value) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(mapUser(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read user", exception);
        }
    }

    private void execute(String sql, String first, String second) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, first);
            statement.setString(2, second);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not execute auth update", exception);
        }
    }

    private void execute(String sql, String first, String second, String third) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, first);
            statement.setString(2, second);
            statement.setString(3, third);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not execute auth update", exception);
        }
    }

    private UserAccount mapUser(ResultSet resultSet) throws SQLException {
        return new UserAccount(
                resultSet.getString("id"),
                resultSet.getString("email"),
                resultSet.getString("display_name"),
                resultSet.getString("role"),
                resultSet.getInt("active") != 0,
                parseInstant(resultSet.getString("created_at")),
                parseInstant(resultSet.getString("updated_at"))
        );
    }

    private ConversationFolder mapFolder(ResultSet resultSet) throws SQLException {
        return new ConversationFolder(
                resultSet.getString("id"),
                resultSet.getString("user_id"),
                resultSet.getString("name"),
                resultSet.getString("system_prompt"),
                parseInstant(resultSet.getString("created_at")),
                parseInstant(resultSet.getString("updated_at"))
        );
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Folder name is required.");
        }
        return name.strip();
    }

    private Instant parseInstant(String value) {
        return value == null || value.isBlank() ? Instant.EPOCH : Instant.parse(value);
    }
}
