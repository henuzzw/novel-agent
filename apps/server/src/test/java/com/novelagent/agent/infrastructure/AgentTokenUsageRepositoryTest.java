package com.novelagent.agent.infrastructure;

import static com.novelagent.support.TestDatabase.env;
import static org.assertj.core.api.Assertions.assertThat;

import com.novelagent.support.DatabaseTest;
import com.novelagent.support.TestDatabase;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Session-local PostgreSQL table only: never insert or update production model tasks. */
@DatabaseTest
class AgentTokenUsageRepositoryTest {
    @Test void scopesWholeProjectAndRollingWindowIncludingFailuresAndMissingUsage() throws Exception {
        String url = TestDatabase.url();
        try (var connection = DriverManager.getConnection(url, env("DB_USERNAME", "novel_app"), env("DB_PASSWORD", ""))) {
            connection.setAutoCommit(false);
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("""
                    CREATE TEMP TABLE agent_run(project_id uuid, status text, started_at timestamptz,
                    input_tokens bigint, output_tokens bigint, actual_input_tokens bigint, actual_output_tokens bigint,
                    estimated_input_tokens bigint, estimated_output_tokens bigint, token_source text, estimated_cost numeric)
                    """);
            var repository = new AgentRunQueryRepository(jdbc);
            UUID project = UUID.randomUUID();
            var now = OffsetDateTime.parse("2026-10-13T17:00:00+08:00");
            insert(jdbc, project, now.minusHours(6), "SUCCEEDED", 1000L, 100L, 10, 2);
            insert(jdbc, project, now.minusHours(5), "FAILED", 200L, 20L, 10, 2);
            insert(jdbc, project, now, "CANCELLED", 50L, 5L, 10, 2);
            insert(jdbc, project, now.minusHours(1), "RUNNING", null, null, 30, 0);
            insert(jdbc, project, now.minusHours(5).minusSeconds(1), "SUCCEEDED", null, null, 5, 1);
            for (int i = 0; i < 101; i++) insert(jdbc, project, now.minusHours(2), "SUCCEEDED", null, null, 1, 1);
            insert(jdbc, project, now.plusSeconds(1), "SUCCEEDED", 900L, 100L, 100, 20);
            insert(jdbc, UUID.randomUUID(), now, "SUCCEEDED", 900L, 100L, 100, 20);
            var usage = repository.usage(project, now);
            assertThat(usage.recentFrom()).isEqualTo(now.minusHours(5));
            assertThat(usage.projectTotal().calls()).isEqualTo(106);
            assertThat(usage.projectTotal().actualInputTokens()).isEqualTo(1250);
            assertThat(usage.projectTotal().actualOutputTokens()).isEqualTo(125);
            assertThat(usage.projectTotal().estimatedInputTokens() + usage.projectTotal().estimatedOutputTokens()).isEqualTo(274);
            assertThat(usage.lastFiveHours().calls()).isEqualTo(104);
            assertThat(usage.lastFiveHours().actualCalls()).isEqualTo(2);
            assertThat(usage.lastFiveHours().actualInputTokens()).isEqualTo(250);
            assertThat(usage.lastFiveHours().actualOutputTokens()).isEqualTo(25);
            assertThat(usage.lastFiveHours().estimatedInputTokens() + usage.lastFiveHours().estimatedOutputTokens()).isEqualTo(256);
            assertThat(usage.lastFiveHours().failures()).isEqualTo(1);
            var empty = repository.usage(UUID.randomUUID(), now);
            assertThat(empty.projectTotal().calls()).isZero();
            assertThat(empty.lastFiveHours().actualCalls()).isZero();
            assertThat(empty.lastFiveHours().tokenSource()).isEqualTo("UNKNOWN");
            connection.rollback();
        }
    }

    private void insert(JdbcTemplate jdbc, UUID project, OffsetDateTime time, String status,
            Long input, Long output, long estimateInput, long estimateOutput) {
        jdbc.update("INSERT INTO agent_run VALUES (?,?,?,?,?,?,?,null,null,?,0)", project, status, time,
                estimateInput, estimateOutput, input, output, input == null ? "ESTIMATED" : "ACTUAL");
    }
}
