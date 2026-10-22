package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.planning.application.SnowflakeStep;
import com.novelagent.planning.domain.SnowflakePlan;
import com.novelagent.platform.support.Json;
import com.novelagent.writing.application.WritingResourceNotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Each stage is saved independently; no database transaction waits for a model. */
@Repository
public class SnowflakePlanStore {
    private final JdbcTemplate jdbc;

    private static final ObjectMapper JSON = new ObjectMapper();

    public SnowflakePlanStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public UUID create(UUID projectId, ModelProvider provider, JsonNode input) {
        // Serialize only run creation with author confirmation; never hold this lock during generation.
        jdbc.queryForObject("SELECT id FROM novel_project WHERE id = ? FOR UPDATE", UUID.class, projectId);
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

    /** Claim a stopped run once; completed prose and its source snapshot remain unchanged. */
    public void resume(UUID projectId, UUID id) {
        requireUpdated(jdbc.update("""
                UPDATE snowflake_planning_run SET status = 'RUNNING', error_message = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE project_id = ? AND id = ? AND status IN ('FAILED', 'CANCELLED')
                """, projectId, id));
    }

    /** Author-triggered recovery discards only a failed dependency suffix; raw agent responses remain intact. */
    public void resumeWithValidPrefix(UUID projectId, UUID id, Map<String, String> expected,
            Map<String, String> retained) {
        requireUpdated(jdbc.update("""
                UPDATE snowflake_planning_run SET status = 'RUNNING', error_message = NULL,
                    steps = CAST(? AS jsonb), core = ?, characters = ?, plot = ?,
                    prepared_materials = CASE WHEN ? THEN prepared_materials ELSE NULL END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE project_id = ? AND id = ? AND status IN ('FAILED', 'CANCELLED')
                    AND steps = CAST(? AS jsonb)
                """, JSON.valueToTree(retained).toString(), retained.get("CORE"), retained.get("CHARACTER_SETTINGS"),
                retained.get("DETAILED_OUTLINE"), retained.containsKey("CHARACTER_SETTINGS"), projectId, id,
                JSON.valueToTree(expected).toString()));
    }

    public void save(UUID projectId, UUID id, String stage, String text) {
        try {
            SnowflakeStep.valueOf(stage);
            requireUpdated(jdbc.update("""
                    UPDATE snowflake_planning_run SET steps = jsonb_set(steps, ARRAY[?]::text[], to_jsonb(?::text)),
                        core = CASE WHEN ? = 'CORE' THEN ? ELSE core END,
                        characters = CASE WHEN ? = 'CHARACTER_SETTINGS' THEN ? ELSE characters END,
                        plot = CASE WHEN ? = 'DETAILED_OUTLINE' THEN ? ELSE plot END,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE project_id = ? AND id = ? AND status = 'RUNNING' AND active_stage = ?
                    """, stage, text, stage, text, stage, text, stage, text, projectId, id, stage));
            return;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知雪花规划阶段", exception);
        }
    }

    public JsonNode input(UUID projectId, UUID id) {
        String value = jdbc.queryForObject("SELECT input_snapshot::text FROM snowflake_planning_run WHERE project_id = ? AND id = ?",
                String.class, projectId, id);
        return tree(value, "雪花来源无法读取");
    }

    /** Save the human-readable step and machine projection atomically, without another model call. */
    @Transactional
    public void saveMaterials(UUID projectId, UUID id, String text, JsonNode materials) {
        save(projectId, id, "CHARACTER_SETTINGS", text);
        requireUpdated(jdbc.update("""
                UPDATE snowflake_planning_run SET prepared_materials = CAST(? AS jsonb)
                 WHERE project_id = ? AND id = ? AND status = 'RUNNING' AND active_stage = 'CHARACTER_SETTINGS'
                """, materials.toString(), projectId, id));
    }

    public JsonNode materials(UUID projectId, UUID id) {
        String value = jdbc.queryForObject("SELECT prepared_materials::text FROM snowflake_planning_run WHERE project_id = ? AND id = ?",
                String.class, projectId, id);
        if (value == null) throw new IllegalArgumentException("雪花第7步缺少资料投影，请重新生成规划");
        return tree(value, "雪花资料无法读取");
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
                this::read, projectId, id).stream().findFirst().orElseThrow(() ->
                        new WritingResourceNotFoundException("雪花规划", id));
    }

    private SnowflakePlan read(ResultSet rs, int row) throws SQLException {
        return new SnowflakePlan(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("mode"), ModelProvider.valueOf(rs.getString("provider")), rs.getString("status"),
                rs.getString("active_stage"), rs.getString("core"), rs.getString("characters"), rs.getString("world"),
                rs.getString("plot"), rs.getString("error_message"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), steps(rs.getString("steps")),
                sourceImportId(rs.getString("input_snapshot")));
    }

    private UUID sourceImportId(String value) {
        String id = tree(value, "雪花来源无法读取").path("sourceImportId").asText("");
        return id.isBlank() ? null : UUID.fromString(id);
    }

    private static Map<String, String> steps(String value) {
        return Json.call(() -> JSON.readValue(value, new TypeReference<Map<String, String>>() { }), "雪花步骤记录无法读取");
    }

    private static JsonNode tree(String value, String message) {
        return Json.call(() -> JSON.readTree(value), message);
    }

    private static void requireUpdated(int count) {
        if (count != 1) throw new IllegalStateException("雪花规划状态已改变，不能保存迟到结果");
    }
}
