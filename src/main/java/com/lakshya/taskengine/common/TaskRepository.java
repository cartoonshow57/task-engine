package com.lakshya.taskengine.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TaskRepository {

    private final JdbcClient jdbc;

    public TaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String type, String payloadJson, int maxAttempts) {
        return jdbc.sql("""
                INSERT INTO tasks (type, payload, max_attempts)
                VALUES (:type, CAST(:payload AS jsonb), :maxAttempts)
                RETURNING id
                """)
                .param("type", type)
                .param("payload", payloadJson)
                .param("maxAttempts", maxAttempts)
                .query(Long.class)
                .single();
    }

    public Optional<Task> findById(long id) {
        return jdbc.sql("SELECT * FROM tasks WHERE id = :id")
                .param("id", id)
                .query(TaskRepository::mapRow)
                .optional();
    }

    public Optional<Task> claimNext(String workerId, int leaseSeconds) {
        return jdbc.sql("""
                UPDATE tasks
                SET status = 'RUNNING',
                    locked_by = :workerId,
                    lease_expires_at = now() + (:leaseSeconds * interval '1 second'),
                    attempts = attempts + 1,
                    updated_at = now()
                WHERE id = (
                    SELECT id FROM tasks
                    WHERE status = 'PENDING' AND run_at <= now()
                    ORDER BY run_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING *
                """)
                .param("workerId", workerId)
                .param("leaseSeconds", leaseSeconds)
                .query(TaskRepository::mapRow)
                .optional();
    }

    public void markSucceeded(long id, String workerId) {
        jdbc.sql("""
                UPDATE tasks
                SET status = 'SUCCEEDED',
                    locked_by = NULL,
                    lease_expires_at = NULL,
                    last_error = NULL,
                    updated_at = now()
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :workerId
                """)
                .param("id", id)
                .param("workerId", workerId)
                .update();
    }

    public void markFailed(long id, String workerId, String error) {
        jdbc.sql("""
                UPDATE tasks
                SET status = CASE WHEN attempts >= max_attempts THEN 'DEAD' ELSE 'PENDING' END,
                    run_at = CASE WHEN attempts >= max_attempts THEN run_at
                                  ELSE now() + (power(2, attempts) * interval '1 second') END,
                    locked_by = NULL,
                    lease_expires_at = NULL,
                    last_error = :error,
                    updated_at = now()
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :workerId
                """)
                .param("id", id)
                .param("workerId", workerId)
                .param("error", error)
                .update();
    }

    public int recoverExpiredTasks() {
        return jdbc.sql("""
                UPDATE tasks
                SET status = CASE WHEN attempts >= max_attempts THEN 'DEAD' ELSE 'PENDING' END,
                    locked_by = NULL,
                    lease_expires_at = NULL,
                    last_error = 'Lease expired: the worker did not finish in time',
                    updated_at = now()
                WHERE status = 'RUNNING' AND lease_expires_at < now()
                """)
                .update();
    }

    private static Task mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Task(
                rs.getLong("id"),
                rs.getString("type"),
                rs.getString("payload"),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getObject("run_at", OffsetDateTime.class),
                rs.getString("locked_by"),
                rs.getObject("lease_expires_at", OffsetDateTime.class),
                rs.getString("last_error"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }
}