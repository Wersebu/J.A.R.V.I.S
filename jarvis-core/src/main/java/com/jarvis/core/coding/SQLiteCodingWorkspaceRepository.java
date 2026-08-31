package com.jarvis.core.coding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.service.CodingService;
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

@Repository
class SQLiteCodingWorkspaceRepository implements CodingWorkspaceRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final SQLiteConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    SQLiteCodingWorkspaceRepository(SQLiteConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public CodingService.CodingWorkspace save(CodingService.CodingWorkspace workspace) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO coding_workspaces
                     (id, owner_user_id, name, windows_path, host, project_type, detected_build_systems_json,
                      git_repository, git_branch, git_head_commit, git_status, autonomy_level, build_command,
                      test_command, last_used_at, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(id) DO UPDATE SET
                     owner_user_id = excluded.owner_user_id,
                     name = excluded.name,
                     windows_path = excluded.windows_path,
                     host = excluded.host,
                     project_type = excluded.project_type,
                     detected_build_systems_json = excluded.detected_build_systems_json,
                     git_repository = excluded.git_repository,
                     git_branch = excluded.git_branch,
                     git_head_commit = excluded.git_head_commit,
                     git_status = excluded.git_status,
                     autonomy_level = excluded.autonomy_level,
                     build_command = excluded.build_command,
                     test_command = excluded.test_command,
                     last_used_at = excluded.last_used_at,
                     updated_at = excluded.updated_at
                     """)) {
            write(statement, workspace);
            statement.executeUpdate();
            return workspace;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save coding workspace", exception);
        }
    }

    @Override
    public Optional<CodingService.CodingWorkspace> findById(String workspaceId) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM coding_workspaces WHERE id = ?")) {
            statement.setString(1, workspaceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read coding workspace", exception);
        }
    }

    @Override
    public List<CodingService.CodingWorkspace> findAll() {
        List<CodingService.CodingWorkspace> workspaces = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM coding_workspaces ORDER BY datetime(last_used_at) DESC");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                workspaces.add(map(resultSet));
            }
            return workspaces;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not list coding workspaces", exception);
        }
    }

    @Override
    public void delete(String workspaceId) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM coding_workspaces WHERE id = ?")) {
            statement.setString(1, workspaceId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not delete coding workspace", exception);
        }
    }

    private void write(PreparedStatement statement, CodingService.CodingWorkspace workspace) throws SQLException {
        statement.setString(1, workspace.id());
        statement.setString(2, workspace.ownerUserId());
        statement.setString(3, nullToEmpty(workspace.name()));
        statement.setString(4, nullToEmpty(workspace.windowsPath()));
        statement.setString(5, workspace.host().name());
        statement.setString(6, nullToEmpty(workspace.projectType()));
        statement.setString(7, json(workspace.detectedBuildSystems()));
        statement.setInt(8, workspace.gitRepository() ? 1 : 0);
        statement.setString(9, nullToEmpty(workspace.gitBranch()));
        statement.setString(10, nullToEmpty(workspace.gitHeadCommit()));
        statement.setString(11, nullToEmpty(workspace.gitStatus()));
        statement.setString(12, workspace.autonomyLevel().name());
        statement.setString(13, nullToEmpty(workspace.buildCommand()));
        statement.setString(14, nullToEmpty(workspace.testCommand()));
        statement.setString(15, instant(workspace.lastUsedAt()));
        statement.setString(16, instant(workspaceCreatedAt(workspace)));
        statement.setString(17, instant(workspace.updatedAt()));
    }

    private CodingService.CodingWorkspace map(ResultSet resultSet) throws SQLException {
        Instant lastUsedAt = parseInstant(resultSet.getString("last_used_at"));
        return new CodingService.CodingWorkspace(
                resultSet.getString("id"),
                resultSet.getString("name"),
                resultSet.getString("windows_path"),
                host(resultSet.getString("host")),
                resultSet.getString("project_type"),
                stringList(resultSet.getString("detected_build_systems_json")),
                resultSet.getInt("git_repository") != 0,
                resultSet.getString("git_branch"),
                resultSet.getString("git_head_commit"),
                resultSet.getString("git_status"),
                autonomy(resultSet.getString("autonomy_level")),
                resultSet.getString("build_command"),
                resultSet.getString("test_command"),
                lastUsedAt,
                resultSet.getString("owner_user_id"),
                parseInstant(resultSet.getString("created_at")),
                parseInstant(resultSet.getString("updated_at"))
        );
    }

    private List<String> stringList(String json) {
        try {
            return objectMapper.readValue(nullToJsonArray(json), STRING_LIST);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private CodingService.WorkspaceHost host(String value) {
        try {
            return CodingService.WorkspaceHost.valueOf(value);
        } catch (RuntimeException exception) {
            return CodingService.WorkspaceHost.SERVER;
        }
    }

    private CodingService.AutonomyLevel autonomy(String value) {
        try {
            return CodingService.AutonomyLevel.valueOf(value);
        } catch (RuntimeException exception) {
            return CodingService.AutonomyLevel.EDIT_AND_TEST;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize coding workspace JSON", exception);
        }
    }

    private Instant workspaceCreatedAt(CodingService.CodingWorkspace workspace) {
        return workspace.createdAt() == null ? workspace.lastUsedAt() : workspace.createdAt();
    }

    private Instant parseInstant(String value) {
        return value == null || value.isBlank() ? Instant.EPOCH : Instant.parse(value);
    }

    private String instant(Instant instant) {
        return (instant == null ? Instant.now() : instant).toString();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String nullToJsonArray(String value) {
        return value == null || value.isBlank() ? "[]" : value;
    }
}
