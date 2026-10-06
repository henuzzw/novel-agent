package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.PlanningBatch;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PlanningBatchJdbcStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PlanningBatchJdbcStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public PlanningBatch insert(UUID projectId, UUID requestId, PlanningBatch.Source source, String hash) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO planning_batch (id, project_id, request_id, source, source_hash)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?)
                """, id, projectId, requestId, json(source), hash);
        return find(projectId, id, false).orElseThrow();
    }

    public Optional<PlanningBatch> find(UUID projectId, UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM planning_batch WHERE project_id = ? AND id = ?"
                + (lock ? " FOR UPDATE" : ""), this::read, projectId, id).stream().findFirst();
    }

    public Optional<PlanningBatch> byRequest(UUID projectId, UUID requestId) {
        return jdbc.query("SELECT * FROM planning_batch WHERE project_id = ? AND request_id = ?",
                this::read, projectId, requestId).stream().findFirst();
    }

    public List<PlanningBatch> list(UUID projectId) {
        return jdbc.query("SELECT * FROM planning_batch WHERE project_id = ? ORDER BY created_at DESC, id",
                this::read, projectId);
    }

    public boolean transition(PlanningBatch batch, PlanningBatch.Status status, List<UUID> checkpoints, UUID outlineId) {
        return jdbc.update("""
                UPDATE planning_batch SET status = ?, checkpoint_ids = CAST(? AS jsonb), outline_version_id = ?,
                    row_version = row_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE project_id = ? AND id = ? AND row_version = ? AND status = ?
                """, status.name(), json(checkpoints), outlineId, batch.projectId(), batch.id(),
                batch.version(), batch.status().name()) == 1;
    }

    private PlanningBatch read(ResultSet row, int index) throws SQLException {
        try {
            return new PlanningBatch(row.getObject("id", UUID.class), row.getObject("project_id", UUID.class),
                    row.getObject("request_id", UUID.class), mapper.readValue(row.getString("source"), PlanningBatch.Source.class),
                    row.getString("source_hash"), mapper.readValue(row.getString("checkpoint_ids"),
                            new TypeReference<List<UUID>>() { }), PlanningBatch.Status.valueOf(row.getString("status")),
                    row.getObject("outline_version_id", UUID.class), row.getLong("row_version"),
                    row.getTimestamp("created_at").toInstant());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("规划批次不能解析", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("规划批次不能序列化", exception);
        }
    }
}
