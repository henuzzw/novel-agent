package com.novelagent.agent.infrastructure;

import com.novelagent.agent.api.AgentRunResponse;
import com.novelagent.agent.api.AgentRunSummary;
import com.novelagent.agent.api.AgentRunPromptResponse;
import com.novelagent.agent.api.AgentRunOutputResponse;
import com.novelagent.agent.api.AgentRunResponse.RequestSnapshotResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentRunQueryRepository {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;

    public AgentRunQueryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<AgentRunResponse> list(UUID projectId) {
        return jdbc.query("""
                SELECT id, stage, provider, status, prompt_preview, input_tokens, output_tokens,
                       token_source, estimated_cost, currency, duration_ms, error_message,
                       started_at, completed_at, actual_input_tokens, actual_output_tokens,
                       estimated_input_tokens, estimated_output_tokens
                  FROM agent_run WHERE project_id = ? ORDER BY started_at DESC LIMIT 100
                """, (rs, row) -> new AgentRunResponse(
                rs.getObject("id", UUID.class), rs.getString("stage"), rs.getString("provider"),
                rs.getString("status"), rs.getString("prompt_preview"), rs.getLong("input_tokens"),
                rs.getLong("output_tokens"), rs.getString("token_source"),
                rs.getBigDecimal("estimated_cost"), rs.getString("currency"),
                rs.getObject("duration_ms", Long.class), rs.getString("error_message"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getObject("actual_input_tokens", Long.class), rs.getObject("actual_output_tokens", Long.class),
                rs.getObject("estimated_input_tokens", Long.class), rs.getObject("estimated_output_tokens", Long.class)), projectId);
    }

    public AgentRunSummary summary(UUID projectId) {
        return jdbc.queryForObject("""
                SELECT count(*) AS calls,
                       count(*) FILTER (WHERE status = 'FAILED') AS failures,
                       coalesce(sum(input_tokens), 0) AS input_tokens,
                       coalesce(sum(output_tokens), 0) AS output_tokens,
                       coalesce(sum(estimated_cost), 0) AS estimated_cost,
                       coalesce(sum(actual_input_tokens), 0) AS actual_input_tokens,
                       coalesce(sum(actual_output_tokens), 0) AS actual_output_tokens,
                       coalesce(sum(estimated_input_tokens), 0) AS estimated_input_tokens,
                       coalesce(sum(estimated_output_tokens), 0) AS estimated_output_tokens,
                       count(*) FILTER (WHERE token_source = 'ACTUAL') AS actual_calls,
                       count(*) FILTER (WHERE token_source = 'ESTIMATED') AS estimated_calls,
                       count(*) FILTER (WHERE token_source = 'UNKNOWN') AS unknown_calls,
                       CASE WHEN count(*) = 0 THEN 'UNKNOWN'
                            WHEN count(DISTINCT token_source) = 1 THEN min(token_source)
                            ELSE 'MIXED' END AS token_source
                  FROM agent_run WHERE project_id = ?
                """, (rs, row) -> new AgentRunSummary(rs.getLong("calls"), rs.getLong("failures"),
                rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                rs.getBigDecimal("estimated_cost"), "CNY", rs.getString("token_source"),
                rs.getLong("actual_input_tokens"), rs.getLong("actual_output_tokens"),
                rs.getLong("estimated_input_tokens"), rs.getLong("estimated_output_tokens"),
                rs.getLong("actual_calls"), rs.getLong("estimated_calls"), rs.getLong("unknown_calls")), projectId);
    }

    public Optional<AgentRunPromptResponse> prompt(UUID projectId, UUID runId) {
        List<AgentRunPromptResponse> matches = jdbc.query("""
                SELECT id, system_prompt, user_prompt, prompt_preview
                  FROM agent_run WHERE project_id = ? AND id = ?
                """, (rs, row) -> new AgentRunPromptResponse(rs.getObject("id", UUID.class),
                rs.getString("system_prompt"), rs.getString("user_prompt"), rs.getString("prompt_preview")),
                projectId, runId);
        return matches.stream().findFirst();
    }

    public Optional<RequestSnapshotResponse> requestSnapshot(UUID projectId, UUID runId) {
        List<RequestSnapshotResponse> matches = jdbc.query("""
                SELECT id, request_snapshot, usage_snapshot, token_source,
                       actual_input_tokens, actual_output_tokens, estimated_input_tokens, estimated_output_tokens
                  FROM agent_run WHERE project_id = ? AND id = ?
                """, (rs, row) -> new RequestSnapshotResponse(rs.getObject("id", UUID.class),
                readJson(rs.getString("request_snapshot")), readJson(rs.getString("usage_snapshot")),
                rs.getString("token_source"), rs.getObject("actual_input_tokens", Long.class),
                rs.getObject("actual_output_tokens", Long.class), rs.getObject("estimated_input_tokens", Long.class),
                rs.getObject("estimated_output_tokens", Long.class)), projectId, runId);
        return matches.stream().findFirst();
    }

    private static JsonNode readJson(String value) {
        if (value == null) return null;
        try {
            return JSON.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid stored agent run metadata", exception);
        }
    }

    public Optional<AgentRunOutputResponse> output(UUID projectId, UUID runId) {
        List<AgentRunOutputResponse> matches = jdbc.query("""
                SELECT id, status, response_text, response_truncated, error_type, error_category, error_detail, duration_ms
                  FROM agent_run WHERE project_id = ? AND id = ?
                """, (rs, row) -> new AgentRunOutputResponse(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getString("response_text"), rs.getBoolean("response_truncated"), rs.getString("error_type"),
                rs.getString("error_category"), rs.getString("error_detail"), rs.getObject("duration_ms", Long.class)),
                projectId, runId);
        return matches.stream().findFirst();
    }
}
