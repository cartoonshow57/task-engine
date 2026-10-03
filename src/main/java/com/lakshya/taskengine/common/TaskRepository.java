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