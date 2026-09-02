package com.jarvis.core.coding;

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
class SQLiteCodingApprovalRepository implements CodingApprovalRepository {

    private final SQLiteConnectionFactory connectionFactory;

    SQLiteCodingApprovalRepository(SQLiteConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public CodingService.CodingApproval save(CodingService.CodingApproval approval) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO coding_approvals
                     (id, task_id, owner_user_id, operation, description, risk_level, arguments_digest,
                      status, created_at, expires_at, decided_at, consumed_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(id) DO UPDATE SET
                     status = excluded.status,
                     decided_at = excluded.decided_at,
                     consumed_at = excluded.consumed_at
                     """)) {
            write(statement, approval);
            statement.executeUpdate();
            return approval;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not save coding approval", exception);
        }
    }

    @Override
    public Optional<CodingService.CodingApproval> findById(String approvalId) {
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM coding_approvals WHERE id = ?")) {
            statement.setString(1, approvalId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read coding approval", exception);
        }
    }

    @Override
    public List<CodingService.CodingApproval> findByTaskId(String taskId) {
        List<CodingService.CodingApproval> approvals = new ArrayList<>();
        try (Connection connection = connectionFactory.openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM coding_approvals WHERE task_id = ? ORDER BY datetime(created_at)")) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    approvals.add(map(resultSet));
                }
            }
            return approvals;
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not list coding approvals", exception);
        }
    }

    private void write(PreparedStatement statement, CodingService.CodingApproval approval) throws SQLException {
        statement.setString(1, approval.id());
        statement.setString(2, approval.taskId());
        statement.setString(3, approval.ownerUserId());
        statement.setString(4, approval.operation());
        statement.setString(5, approval.description());
        statement.setString(6, approval.riskLevel());
        statement.setString(7, approval.argumentsDigest());
        statement.setString(8, approval.status().name());
        statement.setString(9, instant(approval.createdAt()));
        statement.setString(10, instant(approval.expiresAt()));
        statement.setString(11, approval.decidedAt() == null ? "" : approval.decidedAt().toString());
        statement.setString(12, approval.consumedAt() == null ? "" : approval.consumedAt().toString());
    }

    private CodingService.CodingApproval map(ResultSet resultSet) throws SQLException {
        return new CodingService.CodingApproval(
                resultSet.getString("id"),
                resultSet.getString("task_id"),
                resultSet.getString("owner_user_id"),
                resultSet.getString("operation"),
                resultSet.getString("description"),
                resultSet.getString("risk_level"),
                resultSet.getString("arguments_digest"),
                status(resultSet.getString("status")),
                parseInstant(resultSet.getString("created_at")),
                parseInstant(resultSet.getString("expires_at")),
                optionalInstant(resultSet.getString("decided_at")),
                optionalInstant(resultSet.getString("consumed_at"))
        );
    }

    private CodingService.CodingApprovalStatus status(String value) {
        try {
            return CodingService.CodingApprovalStatus.valueOf(value);
        } catch (RuntimeException exception) {
            return CodingService.CodingApprovalStatus.EXPIRED;
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
}
