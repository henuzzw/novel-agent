package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.SnowflakePlan;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Each stage is saved independently; no database transaction waits for a model. */
@Repository
public class SnowflakePlanStore {
    private final JdbcTemplate jdbc;

    public SnowflakePlanStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UUID create(UUID projectId, ModelProvider provider, JsonNode input) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO snowflake_planning_run(id, project_id, mode, provider, input_snapshot)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb))
                """, id, projectId, input.path("mode").asText("NEW_STORY"), provider.name(), input.toString());
        return id;
    }

    public void start(UUID projectId, UUID id, String stage) {
        requireUpdated(jdbc.update("""
                UPDATE snowflake_planning_run SET active_stage = ?, updated_at = CURRENT_TIMESTAMP
                WHERE project_id = ? AND id = ? AND status = 'RUNNING'
                """, stage, projectId, id));
    }

    public void save(UUID projectId, UUID id, String stage, String text) {
        String column = switch (stage) {
            case "CORE" -> "core";
            case "CHARACTERS" -> "characters";
            case "WORLD" -> "world";
            case "PLOT" -> "plot";
            default -> throw new IllegalArgumentException("未知雪花规划阶段");
        };
        requireUpdated(jdbc.update("UPDATE snowflake_planning_run SET " + column
                + " = ?, updated_at = CURRENT_TIMESTAMP WHERE project_id = ? AND id = ?"
                + " AND status = 'RUNNING' AND active_stage = ?", text, projectId, id, stage));
    }

    public void finish(UUID projectId, UUID id, String status, String error) {
        requireUpdated(jdbc.update("""
                UPDATE snowflake_planning_run SET status = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP
                WHERE project_id = ? AND id = ? AND status = 'RUNNING'
                """, status, error, projectId, id));
    }

    public Optional<SnowflakePlan> latest(UUID projectId) {
        return jdbc.query("SELECT * FROM snowflake_planning_run WHERE project_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
                this::read, projectId).stream().findFirst();
    }

    public SnowflakePlan get(UUID projectId, UUID id) {
        return jdbc.query("SELECT * FROM snowflake_planning_run WHERE project_id = ? AND id = ?",
                this::read, projectId, id).stream().findFirst().orElseThrow();
    }

    private SnowflakePlan read(ResultSet rs, int row) throws SQLException {
        return new SnowflakePlan(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("mode"), ModelProvider.valueOf(rs.getString("provider")), rs.getString("status"),
                rs.getString("active_stage"), rs.getString("core"), rs.getString("characters"), rs.getString("world"),
                rs.getString("plot"), rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static void requireUpdated(int count) {
        if (count != 1) throw new IllegalStateException("雪花规划状态已改变，不能保存迟到结果");
    }
}
