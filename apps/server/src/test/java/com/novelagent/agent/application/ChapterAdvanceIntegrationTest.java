package com.novelagent.agent.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.novelagent.agent.api.CreateAutomationRunRequest;
import com.novelagent.agent.domain.AutomationStatus;
import com.novelagent.canon.api.PublishManuscriptRequest;
import com.novelagent.canon.application.CanonPublicationService;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.*;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.domain.*;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.writing.application.*;
import com.novelagent.writing.domain.*;
import com.novelagent.writing.infrastructure.*;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Isolated real migrations, child state and canonical publication; no real model, vector or graph requests. */
@SpringBootTest(properties={"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "logging.file.name=target/chapter-advance-test.log"})
@EnabledIfEnvironmentVariable(named="NOVEL_ADVANCE_DB_TEST", matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChapterAdvanceIntegrationTest {
    private static final String SCHEMA = "advance_test_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired AutomationService service;
    @Autowired AutomationRunStore store;
    @Autowired AutomationPublicationListener notifications;
    @Autowired CanonPublicationService publication;
    @Autowired NovelProjectRepository projects;
    @Autowired CurrentActorProvider actor;
    @Autowired OutlineVersionRepository outlines;
    @Autowired ManuscriptVersionRepository manuscripts;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @MockitoBean DraftLoopModel model;
    @MockitoBean DraftLoopContext contexts;
    @MockitoBean com.novelagent.canon.application.PublishedMemoryService memory;
    @MockitoBean com.novelagent.canon.infrastructure.OutboxPublisher outbox;
    @MockitoBean com.novelagent.memory.infrastructure.SemanticEmbeddingBackfill embeddings;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> "jdbc:postgresql://" + env("DB_HOST","localhost") + ":" + env("DB_PORT","5432")
                + "/" + env("DB_NAME","novel_agent") + "?currentSchema=" + SCHEMA + ",public&sslmode=" + env("DB_SSL_MODE","disable"));
        p.add("spring.flyway.default-schema", () -> SCHEMA); p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }
    @BeforeEach void models() {
        when(contexts.capture(any(), anyInt())).thenAnswer(call -> source(call.getArgument(0), call.getArgument(1)));
        when(contexts.freeze(any(), anyInt(), any())).thenAnswer(call -> source(call.getArgument(0), call.getArgument(1)));
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            DraftLoopRun loop = call.getArgument(0); Consumer<GeneratedManuscript> save = call.getArgument(1);
            save.accept(new GeneratedManuscript(content("旧稿第" + loop.getChapterNumber() + "章"))); return null;
        }).when(model).write(any(), any());
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            ManuscriptContent body = call.getArgument(1); Consumer<DraftCheck> save = call.getArgument(2);
            save.accept(new DraftCheck("检查", body.body().startsWith("旧稿") ? List.of(new DraftCheck.Issue("F1", QualityDimension.FLUENCY,
                    "修订", body.body(), "正文", "", "", "", "澄清")) : List.of())); return null;
        }).when(model).check(any(), any(), any());
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            DraftLoopRun loop = call.getArgument(0); Consumer<DraftJudgment> save = call.getArgument(3);
            save.accept(new DraftJudgment(DraftJudgment.Action.REVISED,
                    List.of(new DraftJudgment.Decision("F1", DraftJudgment.Verdict.ACCEPT, "有依据")),
                    content("修订稿第" + loop.getChapterNumber() + "章"), List.of("明确表达"))); return null;
        }).when(model).judge(any(), any(), any(), any());
    }
    private DraftLoopContext.Source source(UUID project, int chapter) {
        UUID outline = jdbc.queryForObject("SELECT current_outline_version_id FROM novel_project WHERE id=?", UUID.class, project);
        long canon = jdbc.queryForObject("SELECT current_canon_version FROM novel_project WHERE id=?", Long.class, project);
        var basis = new DraftLoopRun.Basis(project + ":" + chapter + ":" + canon, "冻结资料",
                new ManuscriptBasis(outline, "本章计划", mock(ChapterContractContent.class)));
        var context = mock(WritingContextService.Context.class);
        when(context.chapter()).thenReturn(new ChapterPlan(chapter, "标题", "本章计划", ChapterPlanStatus.PLANNED));
        return new DraftLoopContext.Source(basis, context,
                manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(project, chapter).orElse(null));
    }
    private UUID project() {
        UUID id = UUID.randomUUID(), bible = UUID.randomUUID(), outline = UUID.randomUUID();
        projects.saveAndFlush(NovelProject.create(id, actor.currentUserId(), "推进测试", EntryMode.MATERIALS));
        jdbc.update("INSERT INTO story_bible_version(id,project_id,generation_number,schema_version,status,generator_type,content,created_at,updated_at) VALUES (?,?,1,'bible/1','PUBLISHED','TEST','{}',now(),now())", bible, id);
        var arc = new OutlineArc(1, "阶段", "目标", "冲突", "转折", "结果", 2000, 4000,
                List.of(new ChapterPlan(1,"一","计划一",ChapterPlanStatus.PLANNED),new ChapterPlan(2,"二","计划二",ChapterPlanStatus.PLANNED)));
        var version = OutlineVersion.create(outline, id, 1, "TEST", null, bible,
                new OutlineWordBudget(3000,2000,4000,1,2,1500,1000,2000),
                new OutlineContent("书名","主线","两章","节奏",2000,4000,List.of(arc)));
        version.publish(); outlines.saveAndFlush(version);
        jdbc.update("UPDATE novel_project SET current_outline_version_id=?,current_bible_version_id=? WHERE id=?", outline,bible,id);
        return id;
    }
    private UUID start(UUID project, boolean autoPublish, boolean autoMemory) {
        return service.create(project, UUID.randomUUID(), new CreateAutomationRunRequest(1,2,ModelProvider.DEEPSEEK,
                null,false,0,100,3,autoPublish,autoMemory)).id();
    }
    private void await(UUID project, UUID run, AutomationStatus status, int chapter) throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < end) {
            var current = service.get(project, run);
            if (current.status() == status && current.currentChapter() == chapter
                    && (status != AutomationStatus.WAITING_FOR_USER || !current.autoPublish() || !current.autoMemory()
                        || current.waitingReason() != null && current.waitingReason().contains("等待记忆整理"))) return;
            if (current.status() == AutomationStatus.FAILED) fail("推进失败：" + current.errorCode());
            Thread.sleep(40);
        }
        fail("未到达期望状态：" + service.get(project,run));
    }
    private void publish(UUID project, int chapter) {
        var draft = manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(project,chapter).orElseThrow();
        long canon = jdbc.queryForObject("SELECT current_canon_version FROM novel_project WHERE id=?",Long.class,project);
        publication.publish(project,chapter,new PublishManuscriptRequest(draft.getId(),draft.getRowVersion(),canon,null,ModelProvider.DEEPSEEK));
    }
    @Test void authorPublicationCommitsBeforeContinuationRollbackDoesNotAdvanceAndMemoryCanBeDisabled() throws Exception {
        UUID project = project(), run = start(project,false,false);
        await(project,run,AutomationStatus.WAITING_FOR_USER,1);
        assertThat(service.get(project,run).waitingForPublication()).isTrue();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { publish(project,1); tx.setRollbackOnly(); });
        assertThat(service.get(project,run).currentChapter()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM canon_commit WHERE project_id=?",Integer.class,project)).isZero();
        publish(project,1);
        await(project,run,AutomationStatus.WAITING_FOR_USER,2);
        assertThat(jdbc.queryForList("SELECT status FROM canon_memory_job WHERE project_id=?",String.class,project)).containsExactly("SKIPPED");
        publish(project,1); // idempotent publication and repeated notification cannot create a second chapter-2 loop
        publish(project,2);
        await(project,run,AutomationStatus.SUCCEEDED,2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM draft_loop_run WHERE automation_id=?",Integer.class,run)).isEqualTo(2);
        assertThat(store.get(project,run).getSteps()).extracting(s -> s.stage()).containsExactly(
                "DRAFT_A","DRAFT_B","DRAFT_C","DRAFT_B","DRAFT_A","DRAFT_B","DRAFT_C","DRAFT_B");
    }
    @Test void authorizedAutomaticPublicationFinishesRangeAndCreatesIndependentMemoryJobs() throws Exception {
        UUID project = project(), run = start(project,true,true);
        await(project,run,AutomationStatus.WAITING_FOR_USER,1);
        assertThat(service.get(project,run).waitingReason()).contains("等待记忆整理");
        notifications.reconcilePublished();
        assertThat(service.get(project,run).currentChapter()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM draft_loop_run WHERE automation_id=?",Integer.class,run)).isEqualTo(1);
        jdbc.update("UPDATE canon_memory_job SET status='SUCCEEDED' WHERE project_id=?",project);
        notifications.reconcilePublished();
        await(project,run,AutomationStatus.WAITING_FOR_USER,2);
        jdbc.update("UPDATE canon_memory_job SET status='SUCCEEDED' WHERE project_id=?",project);
        notifications.reconcilePublished();
        await(project,run,AutomationStatus.SUCCEEDED,2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM canon_commit WHERE project_id=? AND active",Integer.class,project)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT status FROM canon_memory_job WHERE project_id=?",String.class,project))
                .containsExactlyInAnyOrder("SUCCEEDED","SUCCEEDED");
        assertThat(manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(project,2).orElseThrow().getStatus())
                .isEqualTo(ManuscriptStatus.AUTHOR_ACCEPTED);
    }
    private static ManuscriptContent content(String body) { return new ManuscriptContent("标题",body,"摘要",List.of()); }
    @AfterAll void cleanup() { jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE"); }
    private static String env(String key, String fallback) { return System.getenv().getOrDefault(key,fallback); }
}
