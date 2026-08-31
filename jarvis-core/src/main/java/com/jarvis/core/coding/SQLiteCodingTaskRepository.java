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
import java.util.Map;
import java.util.Optional;

@Repository
class SQLiteCodingTaskRepository implements CodingTaskRepository {

    private static final TypeReference<List<CodingService.PlanStep>> PLAN_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private final SQLiteConnectionFactory connectionFactory;
    private final ObjectMapper objectMapper;

    SQLiteCodingTaskRepository(SQLiteConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        this.connectionFactory = connectionFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public CodingService.CodingTask save(CodingService.CodingTask task) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO coding_tasks
                     (id, workspace_id, owner_user_id, conversation_id, model, prompt, status, plan_json,
                      current_action, iteration, started_at, finished_at, changed_files_json, build_result,
                      test_result, failure_reason, updated_at, final_answer, system_prompt_version,
                      initial_git_snapshot_json, final_git_snapshot_json)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(id) DO UPDATE SET
                     workspace_id = excluded.workspace_id,
                     owner_user_id = excluded.owner_user_id,
                     conversation_id = excluded.conversation_id,
                     model = excluded.model,
                     prompt = excluded.prompt,
                     status = excluded.status,
                     plan_json = excluded.plan_json,
                     current_action = excluded.current_action,
                     iteration = excluded.iteration,
                     finished_at = excluded.finished_at,
                     changed_files_json = excluded.changed_files_json,
                     build_result = excluded.build_result,
                     test_result = excluded.test_result,
                     failure_reason = excluded.failure_reason,
                     updated_at = excluded.updated_at,
                     final_answer = excluded.final_answer,
                     system_prompt_version = excluded.system_prompt_version,
                     initial_git_snapshot_json = excluded.initial_git_snapshot_json,
                     final_git_snapshot_json = excluded.final_git_snapshot_json
                     """)) {
            write(statement, task);
            statement.executeUpdate();
            return task;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save coding task", exception);
        }
    }

    @Override
    public Optional<CodingService.CodingTask> findById(String taskId) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM coding_tasks WHERE id = ?")) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read coding task", exception);
        }
    }

    @Override
    public List<CodingService.CodingTask> findAll() {
        List<CodingService.CodingTask> tasks = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM coding_tasks ORDER BY datetime(started_at) DESC");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                tasks.add(map(resultSet));
            }
            return tasks;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not list coding tasks", exception);
        }
    }

    private void write(PreparedStatement statement, CodingService.CodingTask task) throws SQLException {
        statement.setString(1, task.id());
        statement.setString(2, task.workspaceId());
        statement.setString(3, task.ownerUserId());
        statement.setString(4, nullToEmpty(task.conversationId()));
        statement.setString(5, nullToEmpty(task.model()));
        statement.setString(6, nullToEmpty(task.prompt()));
        statement.setString(7, task.status().name());
        statement.setString(8, json(task.plan()));
        statement.setString(9, nullToEmpty(task.currentAction()));
        statement.setInt(10, task.iteration());
        statement.setString(11, instant(task.startedAt()));
        statement.setString(12, task.finishedAt() == null ? "" : task.finishedAt().toString());
        statement.setString(13, json(task.changedFiles()));
        statement.setString(14, nullToEmpty(task.buildResult()));
        statement.setString(15, nullToEmpty(task.testResult()));
        statement.setString(16, nullToEmpty(task.failureReason()));
        statement.setString(17, instant(task.updatedAt()));
        statement.setString(18, nullToEmpty(task.finalAnswer()));
        statement.setString(19, nullToEmpty(task.systemPromptVersion()));
        statement.setString(20, json(task.initialGitSnapshot()));
        statement.setString(21, json(task.finalGitSnapshot()));
    }

    private CodingService.CodingTask map(ResultSet resultSet) throws SQLException {
        return new CodingService.CodingTask(
                resultSet.getString("id"),
                resultSet.getString("workspace_id"),
                resultSet.getString("conversation_id"),
                resultSet.getString("model"),
                resultSet.getString("prompt"),
                status(resultSet.getString("status")),
                plan(resultSet.getString("plan_json")),
                resultSet.getString("current_action"),
                resultSet.getInt("iteration"),
                parseInstant(resultSet.getString("started_at")),
                optionalInstant(resultSet.getString("finished_at")),
                stringMap(resultSet.getString("changed_files_json")),
                resultSet.getString("build_result"),
                resultSet.getString("test_result"),
                resultSet.getString("failure_reason"),
                resultSet.getString("owner_user_id"),
                parseInstant(resultSet.getString("updated_at")),
                resultSet.getString("final_answer"),
                resultSet.getString("system_prompt_version"),
                gitSnapshot(resultSet.getString("initial_git_snapshot_json")),
                gitSnapshot(resultSet.getString("final_git_snapshot_json"))
        );
    }

    private List<CodingService.PlanStep> plan(String json) {
        try {
            return objectMapper.readValue(nullToJsonArray(json), PLAN_LIST);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private CodingService.CodingTaskStatus status(String value) {
        try {
            return CodingService.CodingTaskStatus.valueOf(value);
        } catch (RuntimeException exception) {
            return CodingService.CodingTaskStatus.INTERRUPTED;
        }
    }

    private Map<String, String> stringMap(String json) {
        try {
            return objectMapper.readValue(nullToJsonObject(json), STRING_MAP);
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private CodingService.GitSnapshot gitSnapshot(String json) {
        try {
            return objectMapper.readValue(nullToJsonObject(json), CodingService.GitSnapshot.class);
        } catch (JsonProcessingException exception) {
            return new CodingService.GitSnapshot("", "", "", "");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize coding task JSON", exception);
        }
    }

    private Instant optionalInstant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
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

    private String nullToJsonObject(String value) {
        return value == null || value.isBlank() ? "{}" : value;
    }
}
