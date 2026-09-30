package com.novelagent.agent.api;

import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent-runs")
public class AgentRunController {
    private final JdbcTemplate jdbc;
    private final NovelProjectRepository projects;
    private final CurrentActorProvider actors;

    public AgentRunController(JdbcTemplate jdbc, NovelProjectRepository projects, CurrentActorProvider actors) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.actors = actors;
    }

    @GetMapping
    public List<AgentRunResponse> list(@PathVariable UUID projectId) {
        requireOwnedProject(projectId);
        return jdbc.query("""
                SELECT id, stage, provider, status, prompt_preview, input_tokens, output_tokens,
                       token_source, estimated_cost, currency, duration_ms, error_message,
                       started_at, completed_at
                  FROM agent_run WHERE project_id = ? ORDER BY started_at DESC LIMIT 100
                """, (rs, row) -> new AgentRunResponse(
                rs.getObject("id", UUID.class), rs.getString("stage"), rs.getString("provider"),
                rs.getString("status"), rs.getString("prompt_preview"), rs.getLong("input_tokens"),
                rs.getLong("output_tokens"), rs.getString("token_source"),
                rs.getBigDecimal("estimated_cost"), rs.getString("currency"),
                rs.getObject("duration_ms", Long.class), rs.getString("error_message"),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class)), projectId);
    }

    @GetMapping("/summary")
    public AgentRunSummary summary(@PathVariable UUID projectId) {
        requireOwnedProject(projectId);
        return jdbc.queryForObject("""
                SELECT count(*) AS calls,
                       count(*) FILTER (WHERE status = 'FAILED') AS failures,
                       coalesce(sum(input_tokens), 0) AS input_tokens,
                       coalesce(sum(output_tokens), 0) AS output_tokens,
                       coalesce(sum(estimated_cost), 0) AS estimated_cost
                  FROM agent_run WHERE project_id = ?
                """, (rs, row) -> new AgentRunSummary(rs.getLong("calls"), rs.getLong("failures"),
                rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                rs.getBigDecimal("estimated_cost"), "CNY", "ESTIMATED"), projectId);
    }

    @GetMapping("/{runId}/prompt")
    public ResponseEntity<AgentRunPromptResponse> prompt(@PathVariable UUID projectId, @PathVariable UUID runId) {
        requireOwnedProject(projectId);
        List<AgentRunPromptResponse> matches = jdbc.query("""
                SELECT id, system_prompt, user_prompt, prompt_preview
                  FROM agent_run WHERE project_id = ? AND id = ?
                """, (rs, row) -> new AgentRunPromptResponse(rs.getObject("id", UUID.class),
                rs.getString("system_prompt"), rs.getString("user_prompt"), rs.getString("prompt_preview")),
                projectId, runId);
        return matches.stream().findFirst()
                .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private void requireOwnedProject(UUID projectId) {
        if (projects.findById(projectId)
                .filter(project -> project.getOwnerId().equals(actors.currentUserId())).isEmpty()) {
            throw new ProjectNotFoundException(projectId);
        }
    }

    public record AgentRunResponse(UUID id, String stage, String provider, String status,
            String promptPreview, long inputTokens, long outputTokens, String tokenSource,
            BigDecimal estimatedCost, String currency, Long durationMs, String errorMessage,
            OffsetDateTime startedAt, OffsetDateTime completedAt) { }

    public record AgentRunSummary(long calls, long failures, long inputTokens, long outputTokens,
            BigDecimal estimatedCost, String currency, String tokenSource) { }

    public record AgentRunPromptResponse(UUID id, String systemPrompt, String userPrompt, String promptPreview) { }
}
