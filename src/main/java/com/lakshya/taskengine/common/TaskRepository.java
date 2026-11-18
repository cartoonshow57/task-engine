package com.lakshya.taskengine.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.EnumMap;
import java.util.Map;
import java.sql.Types;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TaskRepository {

    private final JdbcClient jdbc;

    public TaskRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String type, String payloadJson, int maxAttempts) {
        return insert(type, payloadJson, maxAttempts, 0, null).id();
    }

    public InsertResult insert(String type, String payloadJson, int maxAttempts, String idempotencyKey) {
        return insert(type, payloadJson, maxAttempts, 0, idempotencyKey);
    }

    public InsertResult insert(String type, String payloadJson, int maxAttempts, int delaySeconds,
                               String idempotencyKey) {
        Optional<Long> inserted = jdbc.sql("""
                INSERT INTO tasks (type, payload, max_attempts, run_at, idempotency_key)
                VALUES (:type, CAST(:payload AS jsonb), :maxAttempts,
                        now() + (:delaySeconds * interval '1 second'), :key)
                ON CONFLICT (idempotency_key) DO NOTHING
                RETURNING id
                """)
                .param("type", type)
                .param("payload", payloadJson)
                .param("maxAttempts", maxAttempts)
                .param("delaySeconds", delaySeconds)
                .param("key", idempotencyKey, Types.VARCHAR)
                .query(Long.class)
                .optional();
        if (inserted.isPresent()) {
            return new InsertResult(inserted.get(), true);
        }
        long existing = jdbc.sql("SELECT id FROM tasks WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(Long.class)
                .single();
        return new InsertResult(existing, false);
    }

    public record InsertResult(long id, boolean created) {}

    public boolean matchesRequest(long id, String type, String payloadJson, int maxAttempts) {
        return jdbc.sql("""
                SELECT count(*) FROM tasks
                WHERE id = :id AND type = :type AND payload = CAST(:payload AS jsonb)
                  AND max_attempts = :maxAttempts
                """)
                .param("id", id)
                .param("type", type)
                .param("payload", payloadJson)
                .param("maxAttempts", maxAttempts)
                .query(Long.class)
                .single() > 0;
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

    public boolean markSucceeded(long id, String workerId) {
        return jdbc.sql("""
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
                .update() > 0;
    }

    public boolean markFailed(long id, String workerId, String error) {
        return jdbc.sql("""
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
                .update() > 0;
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

    public Map<TaskStatus, Long> countByStatus() {
        Map<TaskStatus, Long> counts = new EnumMap<>(TaskStatus.class);
        jdbc.sql("SELECT status, count(*) AS n FROM tasks GROUP BY status")
                .query((rs, rowNum) -> Map.entry(TaskStatus.valueOf(rs.getString("status")), rs.getLong("n")))
                .list()
                .forEach(entry -> counts.put(entry.getKey(), entry.getValue()));
        return counts;
    }
    
    public boolean extendLease(long id, String workerId, int leaseSeconds) {
        return jdbc.sql("""
                UPDATE tasks
                SET lease_expires_at = now() + (:leaseSeconds * interval '1 second'),
                    updated_at = now()
                WHERE id = :id AND status = 'RUNNING' AND locked_by = :workerId
                """)
                .param("id", id)
                .param("workerId", workerId)
                .param("leaseSeconds", leaseSeconds)
                .update() > 0;
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