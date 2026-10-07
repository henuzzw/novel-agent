package com.novelagent.writing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.CharacterProfileService;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.writing.application.ReaderExperienceService;
import com.novelagent.writing.domain.ReaderExperienceEvent;
import com.novelagent.writing.domain.ReaderExperiencePlan;
import com.novelagent.writing.domain.ReaderExperiencePlanInput;
import com.novelagent.writing.domain.ReaderExperienceState;
import com.novelagent.writing.domain.ReaderExperienceSubmission;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "NOVEL_READER_EXPERIENCE_DB_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReaderExperienceJdbcStoreTest {
    private final String schema = "reader_experience_test_" + UUID.randomUUID().toString().replace("-", "");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final UUID owner = UUID.randomUUID();
    private final NovelProjectRepository projects = mock(NovelProjectRepository.class);
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private ReaderExperienceJdbcStore store;
    private ReaderExperienceService service;
    private CharacterProfileService profiles;
    private UUID project, manuscript, character, outline;

    @BeforeAll void databaseAndMigration() throws Exception {
        var datasource = new DriverManagerDataSource("jdbc:postgresql://" + env("DB_HOST", "localhost") + ":" + env("DB_PORT", "5432")
                + "/" + env("DB_NAME", "novel_agent") + "?currentSchema=" + schema + "&sslmode=" + env("DB_SSL_MODE", "disable"),
                env("DB_USERNAME", "novel_app"), env("DB_PASSWORD", "change-me"));
        jdbc = new JdbcTemplate(datasource);
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("""
                CREATE TABLE novel_project(id uuid PRIMARY KEY, owner_id uuid NOT NULL, current_outline_version_id uuid);
                CREATE TABLE manuscript_version(id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES novel_project(id),
                    row_version bigint NOT NULL, chapter_number integer NOT NULL, status text NOT NULL,
                    content jsonb NOT NULL, version_number integer NOT NULL, schema_version text NOT NULL);
                CREATE TABLE canon_commit(id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES novel_project(id),
                    chapter_number integer NOT NULL, manuscript_version_id uuid NOT NULL REFERENCES manuscript_version(id),
                    canon_version bigint NOT NULL, active boolean NOT NULL);
                CREATE UNIQUE INDEX canon_active_chapter ON canon_commit(project_id, chapter_number) WHERE active;
                CREATE TABLE outline_version(id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES novel_project(id),
                    row_version bigint NOT NULL, status text NOT NULL, content jsonb NOT NULL);
                CREATE TABLE story_entity(id uuid PRIMARY KEY, project_id uuid NOT NULL REFERENCES novel_project(id),
                    entity_type text NOT NULL, canonical_name text NOT NULL, source_name text, nickname text, title_name text,
                    row_version bigint NOT NULL DEFAULT 0, role_key text, canon_version_to bigint);
                CREATE TABLE entity_alias(id uuid PRIMARY KEY, project_id uuid NOT NULL, entity_id uuid NOT NULL REFERENCES story_entity(id),
                    alias text NOT NULL, alias_type text NOT NULL, canon_version_to bigint);
                """);
        jdbc.execute(resource("V020__character_profiles.sql"));
        jdbc.execute(resource("V039__reader_experience_ledger.sql"));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(datasource));
        var actor = new CurrentActorProvider(owner);
        var names = new CharacterNameService(new ProjectAccessService(projects, actor), mock(StoryBibleVersionRepository.class), jdbc, mapper);
        store = new ReaderExperienceJdbcStore(jdbc, mapper, names);
        service = new ReaderExperienceService(new ProjectAccessService(projects, actor), actor, store, mapper);
        profiles = new CharacterProfileService(new ProjectAccessService(projects, actor), jdbc);
    }

    @BeforeEach void fixture() throws Exception {
        project = UUID.randomUUID(); manuscript = UUID.randomUUID(); character = UUID.randomUUID(); outline = UUID.randomUUID();
        jdbc.update("INSERT INTO novel_project(id, owner_id) VALUES (?, ?)", project, owner);
        when(projects.findById(project)).thenReturn(Optional.of(NovelProject.create(project, owner, "验证", EntryMode.MATERIALS)));
        jdbc.update("INSERT INTO story_entity(id, project_id, entity_type, canonical_name, source_name, role_key) VALUES (?, ?, 'CHARACTER', '林安', '林安', 'PROTAGONIST')", character, project);
        String token = "{{entity:" + character + ":CANONICAL}}";
        jdbc.update("INSERT INTO manuscript_version VALUES (?, ?, 2, 1, 'AUTHOR_ACCEPTED', ?::jsonb, 1, 'manuscript/1')",
                manuscript, project, mapper.writeValueAsString(java.util.Map.of("title", token + "的纸条", "body", token + "把纸条交给我。", "summary", token + "交出纸条。")));
        var chapter = new ChapterPlan(1, "纸条", "林安", "查线索", "交纸条", "签名", "谜题", 1000, 2000);
        var content = new OutlineContent("故事", "前提", "结构", "节奏", 1000, 2000,
                List.of(new OutlineArc(1, "寻找", "目标", "冲突", "转折", "结果", 1000, 2000, List.of(chapter))));
        jdbc.update("INSERT INTO outline_version VALUES (?, ?, 3, 'PUBLISHED', ?::jsonb)", outline, project, mapper.writeValueAsString(content));
        jdbc.update("UPDATE novel_project SET current_outline_version_id = ? WHERE id = ?", outline, project);
    }

    @Test void migrationCrudAndSourceRenderingRoundTripWithIdempotencyAndRenameExpiry() {
        var source = service.source(project, manuscript);
        assertThat(source.body()).isEqualTo("林安把纸条交给我。");
        assertThat(service.sources(project).getFirst().title()).isEqualTo("林安的纸条");
        var input = new ReaderExperiencePlanInput(UUID.randomUUID(), null, ReaderExperiencePlan.Kind.FORESHADOW, "纸条", "找到主人", "纸条", "签名", "选择", 3);
        var plan = transaction.execute(status -> service.create(project, input));
        assertThat(transaction.execute(status -> service.create(project, input)).plan().id()).isEqualTo(plan.plan().id());
        var submission = new ReaderExperienceSubmission(UUID.randomUUID(), 0L, ReaderExperienceState.SET_UP, manuscript, 2L,
                source.fingerprint(), "林安把纸条", "作者提交", true);
        var submitted = transaction.execute(status -> service.submit(project, plan.plan().id(), submission));
        assertThat(submitted.history().getFirst().canon()).isFalse();
        assertThat(submitted.history().getFirst().event().evidenceTokenized()).contains("{{entity:" + character);
        assertThat(transaction.execute(status -> service.submit(project, plan.plan().id(), submission)).history()).hasSize(1);
        jdbc.update("UPDATE story_entity SET canonical_name = '林遥', row_version = row_version + 1 WHERE id = ?", character);
        var stale = service.get(project, plan.plan().id());
        assertThat(stale.stale()).isTrue();
        assertThat(stale.state()).isEqualTo(ReaderExperienceState.SET_UP);
        assertThat(stale.history().getFirst().event().evidence()).isEqualTo("林安把纸条");
        assertThat(stale.history().getFirst().staleReason()).contains("人物姓名");
        assertThat(service.source(project, manuscript).body()).startsWith("林遥");
    }

    @Test void memoryUsesOnlyActiveAcceptedSourcesAndRebuildsAfterReplacement() throws Exception {
        assertThat(service.memory(project).arcs().getFirst().chapters()).isEmpty();
        UUID commit = UUID.randomUUID();
        jdbc.update("INSERT INTO canon_commit VALUES (?, ?, 1, ?, 1, true)", commit, project, manuscript);
        var first = service.memory(project);
        assertThat(first.outlineId()).isEqualTo(outline);
        assertThat(first.outlineRowVersion()).isEqualTo(3);
        assertThat(first.schemaVersion()).isEqualTo("reader-experience-memory/1");
        assertThat(first.arcs().getFirst().number()).isEqualTo(1);
        assertThat(first.arcs().getFirst().chapters().getFirst().summary()).isEqualTo("林安交出纸条。");
        assertThat(first).isEqualTo(service.memory(project));
        UUID replacement = UUID.randomUUID();
        jdbc.update("INSERT INTO manuscript_version VALUES (?, ?, 4, 1, 'AUTHOR_ACCEPTED', ?::jsonb, 2, 'manuscript/1')", replacement, project,
                mapper.writeValueAsString(java.util.Map.of("title", "新正文", "body", "我还回纸条。", "summary", "还回纸条。")));
        jdbc.update("UPDATE canon_commit SET active = false WHERE id = ?", commit);
        jdbc.update("INSERT INTO canon_commit VALUES (?, ?, 1, ?, 2, true)", UUID.randomUUID(), project, replacement);
        assertThat(service.source(project, manuscript).superseded()).isTrue();
        assertThat(service.memory(project).arcs().getFirst().chapters()).hasSize(1);
        assertThat(service.memory(project).arcs().getFirst().chapters().getFirst().manuscriptId()).isEqualTo(replacement);
    }

    @Test void migrationRejectsCrossProjectDraftAndIncorrectSourceVersions() {
        var plan = transaction.execute(status -> service.create(project, new ReaderExperiencePlanInput(UUID.randomUUID(), null,
                ReaderExperiencePlan.Kind.PROMISE, "纸条", "找到主人", "", "", "", null))).plan();
        UUID foreignProject = UUID.randomUUID(), foreignManuscript = UUID.randomUUID();
        jdbc.update("INSERT INTO novel_project(id, owner_id) VALUES (?, ?)", foreignProject, owner);
        jdbc.update("INSERT INTO manuscript_version VALUES (?, ?, 2, 1, 'AUTHOR_ACCEPTED', '{}'::jsonb, 1, 'manuscript/1')", foreignManuscript, foreignProject);
        assertThatThrownBy(() -> store.append(event(plan, foreignManuscript, 2))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> store.append(event(plan, manuscript, 1))).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE manuscript_version SET status = 'DRAFT' WHERE id = ?", manuscript);
        assertThatThrownBy(() -> store.append(event(plan, manuscript, 2))).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE reader_experience_plan SET kind = 'UNKNOWN' WHERE id = ?", plan.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(store.events(project, plan.id())).isEmpty();
    }

    @Test void scopedCharacterProfilesSelectTokenIdsAliasesAndNeverAllCharacters() {
        UUID unrelated = UUID.randomUUID();
        jdbc.update("INSERT INTO story_entity(id, project_id, entity_type, canonical_name, source_name, role_key) VALUES (?, ?, 'CHARACTER', '远方人物', '远方人物', 'SUPPORTING_1')", unrelated, project);
        jdbc.update("INSERT INTO character_profile(character_id, project_id, speech_style) VALUES (?, ?, '轻声'), (?, ?, '大声')", character, project, unrelated, project);
        String scoped = profiles.promptContext(project, "{{entity:" + character + ":CANONICAL}}", List.of());
        assertThat(scoped).contains("林安", "轻声").doesNotContain("远方人物", "大声");
        assertThat(profiles.promptContext(project, null, List.of(unrelated))).contains("远方人物").doesNotContain("林安");
        assertThat(profiles.promptContext(project)).contains("林安", "远方人物");
    }

    private ReaderExperienceEvent event(ReaderExperiencePlan plan, UUID source, long version) {
        return new ReaderExperienceEvent(UUID.randomUUID(), project, plan.id(), 1, plan, ReaderExperienceState.SET_UP,
                source, version, 1, "a".repeat(64), "纸条", "纸条", "说明", null, false, owner, "reader-experience-event/1", Instant.now());
    }

    private String resource(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/" + name)) {
            if (stream == null) throw new IllegalStateException("测试迁移不存在");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @AfterAll void cleanup() { if (jdbc != null) jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
    private static String env(String key, String fallback) { var value = System.getenv(key); return value == null || value.isBlank() ? fallback : value; }
}
