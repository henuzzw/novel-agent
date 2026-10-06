package com.novelagent.agent.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novelagent.agent.api.AgentRunResponse;
import com.novelagent.agent.api.AgentRunSummary;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AgentRunQueryRepositoryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final AgentRunQueryRepository repository = new AgentRunQueryRepository(jdbc);

    @Test
    void snapshotQueryUsesProjectAndRunIdAndPreservesNullForHistoricalRecords() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        ResultSet row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(runId);
        when(row.getString("token_source")).thenReturn("ESTIMATED");
        when(row.getObject("estimated_input_tokens", Long.class)).thenReturn(40L);
        when(jdbc.query(anyString(), AgentRunQueryRepositoryTest.<AgentRunResponse.RequestSnapshotResponse>rowMapper(),
                eq(projectId), eq(runId))).thenAnswer(call -> {
                    String sql = call.getArgument(0);
                    assertThat(sql).contains("WHERE project_id = ? AND id = ?")
                            .doesNotContain("system_prompt", "user_prompt", "prompt_preview");
                    RowMapper<AgentRunResponse.RequestSnapshotResponse> mapper = call.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });

        var historical = repository.requestSnapshot(projectId, runId).orElseThrow();
        assertThat(historical.requestSnapshot()).isNull();
        assertThat(historical.usage()).isNull();
        assertThat(historical.actualInputTokens()).isNull();
        assertThat(historical.estimatedInputTokens()).isEqualTo(40);

        when(row.getString("request_snapshot")).thenReturn("{\"effectiveSettings\":{\"model\":\"deepseek-flash\",\"version\":3}}");
        when(row.getString("usage_snapshot")).thenReturn("{\"inputTokens\":120,\"outputTokens\":20}");
        when(row.getString("token_source")).thenReturn("ACTUAL");
        when(row.getObject("actual_input_tokens", Long.class)).thenReturn(120L);
        var current = repository.requestSnapshot(projectId, runId).orElseThrow();
        assertThat(current.requestSnapshot().at("/effectiveSettings/version").asLong()).isEqualTo(3);
        assertThat(current.actualInputTokens()).isEqualTo(120);
        assertThat(current.estimatedInputTokens()).isEqualTo(40);
    }

    @Test
    void summaryKeepsEstimatedTotalsSeparateFromActualAndReportsMixedSources() throws Exception {
        UUID projectId = UUID.randomUUID();
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("calls")).thenReturn(3L);
        when(row.getLong("input_tokens")).thenReturn(90L);
        when(row.getLong("output_tokens")).thenReturn(20L);
        when(row.getLong("estimated_input_tokens")).thenReturn(90L);
        when(row.getLong("estimated_output_tokens")).thenReturn(20L);
        when(row.getLong("actual_input_tokens")).thenReturn(140L);
        when(row.getLong("actual_output_tokens")).thenReturn(50L);
        when(row.getLong("actual_calls")).thenReturn(1L);
        when(row.getLong("estimated_calls")).thenReturn(1L);
        when(row.getLong("unknown_calls")).thenReturn(1L);
        when(row.getString("token_source")).thenReturn("MIXED");
        when(row.getBigDecimal("estimated_cost")).thenReturn(BigDecimal.ONE);
        when(jdbc.queryForObject(anyString(), AgentRunQueryRepositoryTest.<AgentRunSummary>rowMapper(), eq(projectId)))
                .thenAnswer(call -> {
                    String sql = call.getArgument(0);
                    assertThat(sql).contains("sum(actual_input_tokens)", "sum(estimated_input_tokens)",
                            "count(DISTINCT token_source)", "WHERE project_id = ?");
                    RowMapper<AgentRunSummary> mapper = call.getArgument(1);
                    return mapper.mapRow(row, 0);
                });

        var summary = repository.summary(projectId);
        assertThat(summary.inputTokens()).isEqualTo(90);
        assertThat(summary.actualInputTokens()).isEqualTo(140);
        assertThat(summary.estimatedInputTokens()).isEqualTo(90);
        assertThat(summary.actualCalls()).isEqualTo(1);
        assertThat(summary.estimatedCalls()).isEqualTo(1);
        assertThat(summary.unknownCalls()).isEqualTo(1);
        assertThat(summary.tokenSource()).isEqualTo("MIXED");
    }

    @Test
    void listExposesActualCountersWithoutChangingLegacyEstimatedCounters() throws Exception {
        UUID projectId = UUID.randomUUID();
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("input_tokens")).thenReturn(20L);
        when(row.getObject("estimated_input_tokens", Long.class)).thenReturn(20L);
        when(row.getObject("actual_input_tokens", Long.class)).thenReturn(100L);
        when(row.getString("token_source")).thenReturn("ACTUAL");
        when(jdbc.query(anyString(), AgentRunQueryRepositoryTest.<AgentRunResponse>rowMapper(), eq(projectId)))
                .thenAnswer(call -> {
                    RowMapper<AgentRunResponse> mapper = call.getArgument(1);
                    return List.of(mapper.mapRow(row, 0));
                });
        var run = repository.list(projectId).getFirst();
        assertThat(run.inputTokens()).isEqualTo(20);
        assertThat(run.estimatedInputTokens()).isEqualTo(20);
        assertThat(run.actualInputTokens()).isEqualTo(100);
    }

    private static <T> RowMapper<T> rowMapper() { return any(); }
}
