package com.novelagent.canon.infrastructure;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProjectionStatusRepository {
    private final JdbcTemplate jdbc;

    public ProjectionStatusRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean isPublished(UUID projectId, long canonVersion) {
        String sql = """
                SELECT count(*)
                  FROM outbox_event o
                 WHERE o.payload->>'projectId' = ?
                   AND (o.payload->>'canonVersion')::bigint = ?
                   AND o.published_at IS NOT NULL
                """;
        Integer count = jdbc.queryForObject(sql, Integer.class, projectId.toString(), canonVersion);
        return count != null && count > 0;
    }

    public boolean isProjected(UUID projectId, long canonVersion, String projectionType) {
        String sql = """
                SELECT count(*)
                  FROM outbox_event o
                 WHERE o.payload->>'projectId' = ?
                   AND (o.payload->>'canonVersion')::bigint = ?
                   AND EXISTS (
                       SELECT 1
                         FROM projection_checkpoint p
                        WHERE p.event_id = o.id
                          AND p.projection_type = ?
                   )
                """;
        Integer count = jdbc.queryForObject(
                sql, Integer.class, projectId.toString(), canonVersion, projectionType);
        return count != null && count > 0;
    }

}
