package com.novelagent.prompt.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.novelagent.prompt.application.AgentPromptCatalog;
import com.novelagent.prompt.application.AgentPromptService;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ResourceVersionConflictException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** 隔离 PostgreSQL schema 验证真实迁移、乐观并发、用户范围与配置/历史原子性。 */
@EnabledIfEnvironmentVariable(named = "NOVEL_PROMPT_DB_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentPromptRepositoryDatabaseTest {
    private final String schema = "prompt_test_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate jdbc;
    private AgentPromptRepository repository;
    private TransactionTemplate transaction;

    @BeforeAll void migrateIsolatedSchema() throws Exception {
        var datasource = new DriverManagerDataSource("jdbc:postgresql://" + env("DB_HOST", "localhost") + ":" + env("DB_PORT", "5432")
                + "/" + env("DB_NAME", "novel_agent") + "?currentSchema=" + schema + "&sslmode=" + env("DB_SSL_MODE", "disable"),
                env("DB_USERNAME", "novel_app"), env("DB_PASSWORD", "change-me"));
        jdbc = new JdbcTemplate(datasource);
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("CREATE TABLE codex_agent_session(id uuid PRIMARY KEY)");
        try (var stream = getClass().getResourceAsStream("/db/migration/V049__agent_prompt_settings.sql")) {
            jdbc.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        repository = new AgentPromptRepository(jdbc);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(datasource));
    }

    @Test void persistsEditsAndResetWithImmutableHistoryAndConflictProtection() {
        var user = UUID.randomUUID();
        var service = new AgentPromptService(new AgentPromptCatalog(), repository, new CurrentActorProvider(user));
        assertThat(service.list()).allMatch(value -> value.version() == 0);
        var first = transaction.execute(status -> service.save("OUTLINE", "第一版系统指令", "首段进入矛盾", 0));
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.updatedAt()).isNotNull();
        assertThat(service.resolve("OUTLINE", "默认原文").systemPrompt()).contains("第一版系统指令", "首段进入矛盾");
        assertThatThrownBy(() -> transaction.execute(status -> service.save("OUTLINE", "过期覆盖", "", 0)))
                .isInstanceOf(ResourceVersionConflictException.class);
        assertThat(service.history("OUTLINE")).hasSize(1);
        var reset = transaction.execute(status -> service.reset("OUTLINE", 1));
        assertThat(reset.version()).isEqualTo(2);
        assertThat(reset.customized()).isFalse();
        assertThat(service.history("OUTLINE")).extracting(value -> value.operation()).containsExactly("RESET", "SAVE");
        assertThat(service.history("OUTLINE").getLast().systemPrompt()).isEqualTo("第一版系统指令");
        assertThat(service.resolve("OUTLINE", "默认原文").revision()).isEqualTo("OUTLINE:v2");
    }

    @Test void usersAndImportModesAreIsolatedAndFirstInsertCannotOverwrite() {
        var one = UUID.randomUUID();
        var two = UUID.randomUUID();
        assertThat(transaction.<Boolean>execute(status -> repository.save(one, "IMPORT_REVERSE_BIBLE_ADAPT", "改编规则", "", 0, "SAVE"))).isTrue();
        assertThat(transaction.<Boolean>execute(status -> repository.save(two, "IMPORT_REVERSE_BIBLE_ADAPT", "另一用户规则", "", 0, "SAVE"))).isTrue();
        assertThat(transaction.<Boolean>execute(status -> repository.save(one, "IMPORT_REVERSE_BIBLE_ADAPT", "重复插入", "", 0, "SAVE"))).isFalse();
        assertThat(repository.find(one, "IMPORT_REVERSE_BIBLE_CONTINUE")).isEmpty();
        assertThat(repository.findAll(two)).singleElement().satisfies(value -> assertThat(value.systemPrompt()).isEqualTo("另一用户规则"));
        assertThat(repository.history(one, "IMPORT_REVERSE_BIBLE_ADAPT")).hasSize(1);
    }

    @Test void historyFailureRollsBackTheCurrentConfiguration() {
        var user = UUID.randomUUID();
        transaction.executeWithoutResult(status -> repository.save(user, "MANUSCRIPT", "原值", "", 0, "SAVE"));
        assertThatThrownBy(() -> transaction.execute(status -> repository.save(user, "MANUSCRIPT", "不能留下", "", 1, "INVALID")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.find(user, "MANUSCRIPT").orElseThrow().systemPrompt()).isEqualTo("原值");
        assertThat(repository.find(user, "MANUSCRIPT").orElseThrow().version()).isEqualTo(1);
        assertThat(repository.history(user, "MANUSCRIPT")).hasSize(1);
    }

    @AfterAll void removeOnlyTheTestSchema() {
        if (jdbc != null && schema.matches("prompt_test_[a-f0-9]{32}")) jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }
}
