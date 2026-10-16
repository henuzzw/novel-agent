package com.novelagent.canon.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CanonFactProjector;
import com.novelagent.canon.application.EntityResolutionService;
import com.novelagent.canon.application.TypedCanonMaterializer;
import com.novelagent.planning.application.PlanningMaterialSyncService;
import com.novelagent.writing.domain.FactDecision;
import com.novelagent.writing.domain.FactProposal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "NOVEL_AUTOMATION_DB_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CanonFactProjectorDatabaseTest {
    private final String schema = "canon_projection_test_" + UUID.randomUUID().toString().replace("-", "");
    private EntityResolutionService resolver;
    private final PlanningMaterialSyncService planning = mock(PlanningMaterialSyncService.class);
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private AnnotationConfigApplicationContext context;
    private TypedCanonMaterializer materializer;
    private UUID project;
    private UUID commit;
    private UUID subject;
    private UUID target;

    @BeforeAll
    void createIsolatedTablesAndWireStrategies() throws Exception {
        var datasource = new DriverManagerDataSource("jdbc:postgresql://" + env("DB_HOST", "localhost")
                + ":" + env("DB_PORT", "5432") + "/" + env("DB_NAME", "novel_agent")
                + "?currentSchema=" + schema + "&sslmode=" + env("DB_SSL_MODE", "disable"),
                env("DB_USERNAME", "novel_app"), env("DB_PASSWORD", "change-me"));
        jdbc = new JdbcTemplate(datasource);
        jdbc.execute("CREATE SCHEMA " + schema);
        // Only the parent identities are fixtures; projection tables use the real migration.
        jdbc.execute("CREATE TABLE novel_project(id uuid PRIMARY KEY); CREATE TABLE canon_commit(id uuid PRIMARY KEY)");
        for (String migration : List.of("V011__typed_canon_memory.sql", "V012__entity_resolution.sql")) {
            try (var resource = getClass().getResourceAsStream("/db/migration/" + migration)) {
                if (resource == null) {
                    throw new IllegalStateException("类型化正史迁移不存在：" + migration);
                }
                jdbc.execute(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        transactions = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        context = new AnnotationConfigApplicationContext();
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.registerBean(PlanningMaterialSyncService.class, () -> planning);
        context.register(EventFactProjector.class, StateFactProjector.class, RelationshipFactProjector.class,
                KnowledgeFactProjector.class, ForeshadowFactProjector.class, JdbcCanonFactStore.class,
                JdbcEntityResolutionStore.class, EntityResolutionService.class, TypedCanonMaterializer.class);
        context.refresh();
        assertThat(context.getBeansOfType(CanonFactProjector.class)).hasSize(5);
        materializer = context.getBean(TypedCanonMaterializer.class);
        resolver = context.getBean(EntityResolutionService.class);
    }

    @BeforeEach
    void identities() {
        project = UUID.randomUUID();
        commit = UUID.randomUUID();
        subject = UUID.randomUUID();
        target = UUID.randomUUID();
        jdbc.update("INSERT INTO novel_project VALUES (?)", project);
        jdbc.update("INSERT INTO canon_commit VALUES (?)", commit);
        for (var entry : List.of(java.util.Map.entry(subject, "甲"), java.util.Map.entry(target, "乙"))) {
            jdbc.update("""
                    INSERT INTO story_entity(id, project_id, entity_type, canonical_name, canon_version_from, source_commit_id)
                    VALUES (?, ?, 'CHARACTER', ?, 1, ?)
                    """, entry.getKey(), project, entry.getValue(), commit);
        }
    }

    @Test
    void springStrategiesPersistBothAliasesAndRetireEveryProjection() {
        List<FactProposal> facts = new ArrayList<>();
        for (String type : List.of("EVENT", "EVENT_CREATE", "STATE", "STATE_CHANGE", "RELATION", "RELATION_CHANGE",
                "KNOWLEDGE", "KNOWLEDGE_CHANGE", "FORESHADOW", "FORESHADOW_CHANGE")) {
            facts.add(new FactProposal(type, type, "甲", type.startsWith("STATE") ? "位置" : "发现", "乙", "原文证据", FactDecision.ACCEPTED));
        }
        facts.add(new FactProposal("UNKNOWN", "UNREGISTERED", "甲", "发现", "乙", "证据", FactDecision.ACCEPTED));
        facts.add(new FactProposal("UNKNOWN_STATE", "STATE_CHANGE", "甲", "无法映射的状态", "乙", "证据", FactDecision.ACCEPTED));
        facts.add(new FactProposal("SELF", "RELATION_CHANGE", "甲", "信任", "甲", "证据", FactDecision.ACCEPTED));
        transactions.executeWithoutResult(status -> materializer.materialize(project, 3, commit, 1, facts));
        assertThat(count("story_fact", false)).isEqualTo(13);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM story_fact WHERE source_commit_id = ? AND subject_entity_id IS NULL",
                Integer.class, commit)).isEqualTo(3);
        for (String table : projectionTables()) {
            assertThat(count(table, false)).as(table).isEqualTo(2);
        }
        assertThat(jdbc.queryForList("SELECT after_value #>> '{}' FROM entity_state_change WHERE source_commit_id = ?", String.class, commit))
                .containsExactly("乙", "乙");
        transactions.executeWithoutResult(status -> materializer.retire(commit, 2));
        assertThat(count("story_fact", true)).isEqualTo(13);
        for (String table : projectionTables()) {
            assertThat(count(table, true)).as(table).isEqualTo(2);
        }
    }

    @Test
    void failedStrategyRollsBackEarlierProjectionsAndGenericFacts() {
        var broken = mock(CanonFactProjector.class);
        when(broken.factTypes()).thenReturn(Set.of("BROKEN"));
        doThrow(new IllegalStateException("projection failed")).when(broken).project(any());
        var strategies = new ArrayList<>(context.getBeansOfType(CanonFactProjector.class).values());
        strategies.add(broken);
        var failing = new TypedCanonMaterializer(new JdbcCanonFactStore(jdbc), strategies, resolver, planning);
        var facts = List.of(
                new FactProposal("F1", "EVENT", "新角色", "发现", "乙", "证据", FactDecision.ACCEPTED),
                new FactProposal("F2", "BROKEN", "甲", "发现", "乙", "证据", FactDecision.ACCEPTED));
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> failing.materialize(project, 1, commit, 1, facts)))
                .isInstanceOf(IllegalStateException.class).hasMessage("projection failed");
        assertThat(count("story_fact", false)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entity_mention WHERE source_commit_id = ?", Integer.class, commit)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM story_entity WHERE project_id = ?", Integer.class, project)).isEqualTo(2);
        for (String table : projectionTables()) {
            assertThat(count(table, false)).as(table).isZero();
        }
    }

    private List<String> projectionTables() {
        return List.of("story_event", "entity_state_change", "story_relationship", "character_knowledge", "foreshadow");
    }

    @Test
    void resolutionPreservesProjectTypeAmbiguityAndMentionUpdates() {
        jdbc.update("""
                INSERT INTO entity_alias(id, project_id, entity_id, alias, alias_type, canon_version_from)
                VALUES (?, ?, ?, '阿甲', 'CONFIRMED', 1)
                """, UUID.randomUUID(), project, subject);
        assertThat(resolver.resolve(project, commit, 1, "alias", "SUBJECT", "CHARACTER", "阿甲", null, "旧证据"))
                .isEqualTo(subject);
        assertThat(resolver.resolve(project, commit, 1, "alias", "SUBJECT", "CHARACTER", "乙", target.toString(), "新证据"))
                .isEqualTo(target);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entity_mention WHERE source_commit_id = ?", Integer.class, commit))
                .isEqualTo(1);
        var mention = jdbc.queryForMap("SELECT * FROM entity_mention WHERE source_commit_id = ?", commit);
        assertThat(mention).containsEntry("resolved_entity_id", target)
                .containsEntry("resolution_method", "REQUESTED_ID").containsEntry("evidence_ref", "新证据");
        assertThatThrownBy(() -> resolver.resolve(UUID.randomUUID(), commit, 1, "other", "SUBJECT", "CHARACTER",
                "甲", subject.toString(), "证据")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不属于当前项目");
        assertThatThrownBy(() -> resolver.resolve(project, commit, 1, "wrong-type", "SUBJECT", "ITEM",
                "甲", subject.toString(), "证据")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("类型不符");
        jdbc.update("""
                INSERT INTO entity_alias(id, project_id, entity_id, alias, alias_type, canon_version_from)
                VALUES (?, ?, ?, '阿甲', 'CONFIRMED', 1)
                """, UUID.randomUUID(), project, target);
        assertThatThrownBy(() -> resolver.resolve(project, commit, 1, "ambiguous", "SUBJECT", "CHARACTER", "阿甲", null, "证据"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("多个对象");
        assertThatThrownBy(() -> resolver.resolve(project, commit, 1, "pronoun", "SUBJECT", "CHARACTER", "她", null, "证据"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("无法确定代词");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM entity_mention WHERE source_commit_id = ?", Integer.class, commit))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM story_entity WHERE project_id = ?", Integer.class, project))
                .isEqualTo(2);
    }

    private int count(String table, boolean retired) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE source_commit_id = ? AND canon_version_to "
                + (retired ? "= 2" : "IS NULL"), Integer.class, commit);
    }

    @AfterAll
    void cleanup() {
        if (context != null) {
            context.close();
        }
        if (jdbc != null) {
            jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private static String env(String key, String fallback) {
        return System.getenv().getOrDefault(key, fallback);
    }
}
