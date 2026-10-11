package com.jarvis.core.google;

import com.jarvis.memory.sqlite.SQLiteConnectionFactory;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * Accounts a Jarvis user connected (Gmail, Google Calendar, later Spotify): one row per user and service, with the refresh
 * token encrypted by {@link SecretCipher}. Nothing here is ever shared between users.
 */
@Repository
public class ConnectedAccountRepository {

    /** One connected account, secret already decrypted. */
    public record ConnectedAccount(String userId, String provider, String accountEmail, String secret, Instant updatedAt) {
    }

    private final SQLiteConnectionFactory connectionFactory;
    private final SecretCipher cipher;
    private volatile boolean tableReady;

    public ConnectedAccountRepository(SQLiteConnectionFactory connectionFactory, SecretCipher cipher) {
        this.connectionFactory = connectionFactory;
        this.cipher = cipher;
    }

    public Optional<ConnectedAccount> find(String userId, String provider) {
        ensureTable();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT account_email, secret, updated_at FROM connected_accounts WHERE user_id = ? AND provider = ?")) {
            statement.setString(1, userId);
            statement.setString(2, provider);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new ConnectedAccount(userId, provider, resultSet.getString("account_email"),
                        cipher.decrypt(resultSet.getString("secret"), context(userId, provider)),
                        Instant.parse(resultSet.getString("updated_at"))));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read connected account", exception);
        }
    }

    public void save(String userId, String provider, String accountEmail, String secret) {
        ensureTable();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO connected_accounts (user_id, provider, account_email, secret, updated_at)
                     VALUES (?, ?, ?, ?, ?)
                     ON CONFLICT(user_id, provider) DO UPDATE SET
                     account_email = excluded.account_email,
                     secret = excluded.secret,
                     updated_at = excluded.updated_at
                     """)) {
            statement.setString(1, userId);
            statement.setString(2, provider);
            statement.setString(3, accountEmail == null ? "" : accountEmail);
            statement.setString(4, cipher.encrypt(secret, context(userId, provider)));
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save connected account", exception);
        }
    }

    public void delete(String userId, String provider) {
        ensureTable();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM connected_accounts WHERE user_id = ? AND provider = ?")) {
            statement.setString(1, userId);
            statement.setString(2, provider);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not remove connected account", exception);
        }
    }

    private static String context(String userId, String provider) {
        return userId + "|" + provider;
    }

    private void ensureTable() {
        if (tableReady) {
            return;
        }
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     CREATE TABLE IF NOT EXISTS connected_accounts (
                         user_id TEXT NOT NULL,
                         provider TEXT NOT NULL,
                         account_email TEXT NOT NULL DEFAULT '',
                         secret TEXT NOT NULL,
                         updated_at TEXT NOT NULL,
                         PRIMARY KEY (user_id, provider)
                     )
                     """)) {
            statement.executeUpdate();
            tableReady = true;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not prepare connected accounts table", exception);
        }
    }
}
