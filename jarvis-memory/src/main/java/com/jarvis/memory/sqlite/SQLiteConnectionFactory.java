package com.jarvis.memory.sqlite;

import com.jarvis.memory.cognitive.MemoryProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Creates SQLite connections for cognitive memory stores.
 */
@Component
public class SQLiteConnectionFactory {

    private final MemoryProperties properties;

    /**
     * Creates the SQLite connection factory.
     *
     * @param properties memory properties
     */
    public SQLiteConnectionFactory(MemoryProperties properties) {
        this.properties = properties;
    }

    /**
     * Opens a new SQLite connection.
     *
     * @return database connection
     */
    public Connection openConnection() {
        try {
            Path databasePath = Path.of(properties.databasePath());
            Path parent = databasePath.toAbsolutePath().getParent();
            if (parent != null) {
                parent.toFile().mkdirs();
            }
            return DriverManager.getConnection("jdbc:sqlite:" + properties.databasePath());
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not open SQLite memory database", exception);
        }
    }
}
