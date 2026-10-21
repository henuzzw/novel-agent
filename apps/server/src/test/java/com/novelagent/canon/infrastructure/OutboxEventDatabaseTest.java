package com.novelagent.canon.infrastructure;

import static com.novelagent.support.TestDatabase.env;
import static org.assertj.core.api.Assertions.assertThat;

import com.novelagent.support.DatabaseTest;
import com.novelagent.support.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 在隔离 schema 中验证 V065 迁移与可发布事件查询：退避中的事件及其同聚合后续事件暂不发布。 */
@DatabaseTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxEventDatabaseTest {
    private final String schema = "outbox_test_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate jdbc;

    @BeforeAll void migrateIsolatedSchema() throws Exception {
        var datasource = new DriverManagerDataSource(TestDatabase.url() + "?currentSchema=" + schema + "&sslmode=" + env("DB_SSL_MODE", "disable"),
                env("DB_USERNAME", "novel_app"), env("DB_PASSWORD", "change-me"));
        jdbc = new JdbcTemplate(datasource);
        jdbc.execute("CREATE SCHEMA " + schema);
        String v008 = resource("V008__canon_and_projection_outbox.sql");
        int start = v008.indexOf("CREATE TABLE outbox_event");
        int end = v008.indexOf(';', v008.indexOf("CREATE INDEX idx_outbox_unpublished")) + 1;
        jdbc.execute(v008.substring(start, end));
        jdbc.execute(resource("V065__outbox_retry_backoff.sql"));
    }

    @AfterAll void dropSchema() {
        if (jdbc != null) jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    @Test void backoffDefersOnlyTheFailingAggregateAndKeepsOrder() {
        UUID blocked = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        UUID failing = insert(blocked, "now() - interval '3 minutes'", "now() + interval '5 minutes'");
        UUID later = insert(blocked, "now() - interval '2 minutes'", null);
        UUID first = insert(healthy, "now() - interval '1 minute'", null);
        UUID due = insert(healthy, "now()", "now() - interval '1 second'");

        List<UUID> ids = new NamedParameterJdbcTemplate(jdbc).queryForList(
                OutboxEventRepository.PUBLISHABLE_SQL.replace("o.*", "o.id"), Map.of("limit", 20), UUID.class);

        assertThat(ids).containsExactly(first, due).doesNotContain(failing, later);
    }

    private UUID insert(UUID aggregate, String createdAt, String nextAttemptAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO outbox_event(id, aggregate_id, event_type, topic, payload, attempts, created_at, next_attempt_at) "
                + "VALUES (?, ?, 'CanonCommitted', 'topic', '{}'::jsonb, 0, " + createdAt + ", "
                + (nextAttemptAt == null ? "NULL" : nextAttemptAt) + ")", id, aggregate);
        return id;
    }

    private String resource(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

}
