package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.PlanningCheckpoint;
import com.novelagent.planning.domain.PlanningCheckpointResult;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PlanningCheckpointJdbcStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PlanningCheckpointJdbcStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public PlanningCheckpoint insert(UUID projectId, String key, int from, int to, PlanningCheckpoint.Source source) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO planning_checkpoint
                    (id, project_id, chunk_key, chapter_from, chapter_to, source_bible_version_id,
                     source_bible_row_version, creative_strategy, policy_version, provider, author_instruction, dependency_hash,
                     dependencies, schema_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, id, projectId, key, from, to, source.bibleId(), source.bibleRowVersion(),
                source.creativeStrategy().strategy().name(), source.creativeStrategy().policyVersion(),
                source.provider().name(), source.instruction(), source.dependencyHash(),
                json(source.dependencies()), PlanningCheckpoint.SCHEMA_VERSION);
        return find(projectId, id, false).orElseThrow();
    }

    public Optional<PlanningCheckpoint> find(UUID projectId, UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM planning_checkpoint WHERE project_id = ? AND id = ?"
                + (lock ? " FOR UPDATE" : ""), this::read, projectId, id).stream().findFirst();
    }

    public Optional<PlanningCheckpoint> matching(UUID projectId, String key, String dependencyHash) {
        return jdbc.query("SELECT * FROM planning_checkpoint WHERE project_id = ? AND chunk_key = ? AND dependency_hash = ?",
                this::read, projectId, key, dependencyHash).stream().findFirst();
    }

    public List<PlanningCheckpoint> list(UUID projectId) {
        return jdbc.query("SELECT * FROM planning_checkpoint WHERE project_id = ? ORDER BY created_at DESC, id",
                this::read, projectId);
    }

    public boolean transition(PlanningCheckpoint expected, PlanningCheckpoint.Status status, long attempt,
            PlanningCheckpointResult result, String failure) {
        return jdbc.update("""
                UPDATE planning_checkpoint
                   SET status = ?, attempt = ?, result = CAST(? AS jsonb), failure = ?,
                       row_version = row_version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE project_id = ? AND id = ? AND status = ? AND attempt = ? AND row_version = ?
                """, status.name(), attempt, result == null ? null : json(result), failure,
                expected.projectId(), expected.id(), expected.status().name(), expected.attempt(), expected.version()) == 1;
    }

    private PlanningCheckpoint read(ResultSet row, int index) throws SQLException {
        var source = new PlanningCheckpoint.Source(row.getObject("source_bible_version_id", UUID.class),
                row.getLong("source_bible_row_version"), new CreativeStrategyPolicy(
                CreativeStrategy.valueOf(row.getString("creative_strategy")), row.getInt("policy_version")),
                ModelProvider.valueOf(row.getString("provider")), row.getString("author_instruction"),
                row.getString("dependency_hash"), dependencies(row.getString("dependencies")));
        String result = row.getString("result");
        return new PlanningCheckpoint(row.getObject("id", UUID.class), row.getObject("project_id", UUID.class),
                row.getString("chunk_key"), row.getInt("chapter_from"), row.getInt("chapter_to"), source,
                PlanningCheckpoint.Status.valueOf(row.getString("status")), row.getLong("attempt"), row.getLong("row_version"),
                result == null ? null : result(result), row.getString("failure"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }

    private String json(Object result) {
        try { return mapper.writeValueAsString(result); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("规划分块不能序列化", exception); }
    }

    private List<PlanningCheckpoint.Dependency> dependencies(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<PlanningCheckpoint.Dependency>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("规划依赖不能解析", exception);
        }
    }

    private PlanningCheckpointResult result(String json) {
        try { return mapper.readValue(json, PlanningCheckpointResult.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("规划分块不能解析", exception); }
    }
}
