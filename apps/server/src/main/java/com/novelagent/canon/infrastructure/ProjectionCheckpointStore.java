package com.novelagent.canon.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class ProjectionCheckpointStore {
    private final JdbcTemplate jdbc;

    ProjectionCheckpointStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    boolean isCompleted(UUID eventId, ProjectionType type) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM projection_checkpoint WHERE event_id = ? AND projection_type = ?",
                Integer.class,
                eventId,
                type.name());
        return count != null && count > 0;
    }

    void markCompleted(UUID eventId, ProjectionType type) {
        jdbc.update(
                """
                INSERT INTO projection_checkpoint(event_id, projection_type, projected_at)
                VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                eventId,
                type.name(),
                Timestamp.from(Instant.now()));
    }

    enum ProjectionType {
        PGVECTOR,
        NEO4J
    }
}
