package com.novelagent.modelaccess.infrastructure;

import static com.novelagent.support.TestDatabase.env;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.canon.infrastructure.OutboxPublisher;
import com.novelagent.ingest.application.ImportAnalysisRunner;
import com.novelagent.ingest.application.ImportAnalysisStore;
import com.novelagent.ingest.application.WorkImportService;
import com.novelagent.memory.infrastructure.SemanticEmbeddingBackfill;
import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.modelaccess.domain.CodexSessionPolicy;
import com.novelagent.modelaccess.application.StructuredModelGateway;
import com.novelagent.project.application.CreativeBoundaryService;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.domain.CreativeBoundaries;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.prompt.application.AgentPromptDefaults;
import com.novelagent.support.DatabaseTest;
import com.novelagent.support.TestDatabase;
import com.novelagent.writing.application.DraftLoopContext;
import com.novelagent.writing.application.DraftLoopService;
import com.novelagent.writing.application.DraftLoopStore;
import com.novelagent.writing.application.GeneratedManuscript;
import com.novelagent.writing.application.WritingContextService;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.DraftCheck;
import com.novelagent.writing.domain.DraftJudgment;
import com.novelagent.writing.domain.DraftLoopRun;
import com.novelagent.writing.domain.ManuscriptBasis;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.QualityDimension;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 全部迁移与 Spring 装配的隔离验证；真实数据库/API/记录器，外部模型使用替身。 */
@SpringBootTest(properties = { "spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "logging.file.name=target/prompt-integration-test.log" })
@AutoConfigureMockMvc
@DatabaseTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentPromptIntegrationTest {
    private static final String SCHEMA = "prompt_app_test_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private StructuredModelGateway models;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NovelProjectRepository projects;
    @Autowired private CurrentActorProvider actor;
    @Autowired private CreativeBoundaryService boundaries;
    @Autowired private WorkImportService imports;
    @Autowired private ImportAnalysisStore analyses;
    @Autowired private ImportAnalysisRunner analysisRunner;
    @Autowired private DraftLoopStore draftLoops;
    @Autowired private DraftLoopService draftLoopService;
    @Autowired private ManuscriptVersionRepository manuscripts;
    @MockitoBean private DraftLoopContext loopContexts;
    @MockitoBean private CodexAppServerClient codex;
    @MockitoBean private DeepSeekStructuredOutputClient deepSeek;
    @MockitoBean private OutboxPublisher outbox;
    @MockitoBean private SemanticEmbeddingBackfill embeddings;

    @Test void sourceAssessmentAndIndependentAuthorChoicesPersistThroughRealDatabaseAndPlainTextWire() throws Exception {
        var created = mvc.perform(multipart("/api/v1/projects/from-story")
                .param("name", "边界设置隔离验证").param("text", "高一时，我按成绩选座。")
                .param("worldMode", "REALISTIC_FICTION").param("adaptationScope", "RESTRUCTURE"))
                .andExpect(status().isCreated()).andReturn();
        UUID explicit = UUID.fromString(mapper.readTree(created.getResponse().getContentAsByteArray()).path("id").asText());
        assertThat(boundaries.get(explicit).effectiveWorld()).isEqualTo(CreativeBoundaries.WorldMode.REALISTIC_FICTION);
        assertThat(boundaries.get(explicit).effectiveScope()).isEqualTo(CreativeBoundaries.AdaptationScope.RESTRUCTURE);

        UUID projectId = UUID.randomUUID();
        projects.saveAndFlush(NovelProject.create(projectId, actor.currentUserId(), "自动世界判断验证", EntryMode.MATERIALS));
        var imported = imports.paste(projectId, "高一时，我按成绩选座。");
        UUID chapterId = imported.chapters().getFirst().id();
        var report = analyses.create(projectId, imported.id(), new ImportAnalysisStore.Create(UUID.randomUUID(), ModelProvider.DEEPSEEK));
        String raw = """
                ## @summary
                本段为现实高中选座。
                ## @items
                无
                ## @worldAssessment/worldMode
                REALISTIC
                ## @worldAssessment/reason
                原文为高中日常，未给定架空设定。
                ## @worldAssessment/evidence/1/chapterId
                %s
                ## @worldAssessment/evidence/1/quote
                高一时，我按成绩选座。
                ## @worldAssessment/evidence/1/occurrence
                0
                """.formatted(chapterId);
        var settings = new EffectiveSettings(ModelProvider.DEEPSEEK, "deepseek-flash", "none", 0L);
        when(deepSeek.effectiveSettings()).thenReturn(settings);
        when(deepSeek.request(anyString(), anyString(), anyString(), any(), anyInt(), any()))
                .thenReturn(new DeepSeekStructuredOutputClient.ResponseResult(raw, null));
        var finished = analysisRunner.next(projectId, imported.id(), report.report().id(), report.report().version());
        assertThat(finished.report().status()).isEqualTo("REVIEW");
        assertThat(boundaries.get(projectId).assessed()).isTrue();
        assertThat(boundaries.get(projectId).effectiveScope()).isEqualTo(CreativeBoundaries.AdaptationScope.PRESERVE_CORE);
        var stored = mapper.readTree(jdbc.queryForObject("SELECT settings::text FROM novel_project WHERE id = ?", String.class, projectId));
        assertThat(stored.at("/creativeBoundaries/assessedWorld").asText()).isEqualTo("REALISTIC");
        assertThat(stored.at("/creativeBoundaries/sourceImportId").asText()).isEqualTo(imported.id().toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE project_id = ?", Integer.class, projectId)).isEqualTo(1);
        var current = boundaries.get(projectId);
        mvc.perform(put("/api/v1/projects/" + projectId + "/settings/creative-boundaries").contentType(MediaType.APPLICATION_JSON)
                .content("{\"worldMode\":\"ALTERNATE\",\"adaptationScope\":\"RESTRUCTURE\",\"version\":" + current.version() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.effectiveWorld").value("ALTERNATE"));
        mvc.perform(put("/api/v1/projects/" + projectId + "/settings/creative-boundaries").contentType(MediaType.APPLICATION_JSON)
                .content("{\"worldMode\":\"AUTO\",\"adaptationScope\":\"AUTO\",\"version\":" + current.version() + "}"))
                .andExpect(status().isConflict());
        var updated = boundaries.get(projectId);
        boundaries.update(projectId, CreativeBoundaries.WorldMode.AUTO,
                CreativeBoundaries.AdaptationScope.AUTO, updated.version(), true);
        assertThat(boundaries.get(projectId).assessed()).isFalse();
        assertThat(projects.findById(projectId).orElseThrow().getCurrentBibleVersionId()).isNull();
    }

    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry properties) {
        String url = TestDatabase.url() + "?currentSchema=" + SCHEMA + ",public&sslmode=" + env("DB_SSL_MODE", "disable");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Test void promptsComeFromBundledFilesAndTheEditingApiIsRetired() throws Exception {
        mvc.perform(get("/api/v1/settings/prompts")).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/settings/prompts/OUTLINE").contentType(MediaType.APPLICATION_JSON)
                .content("{\"systemPrompt\":\"x\",\"sessionSystemPrompt\":\"y\",\"guidance\":\"\",\"version\":0}"))
                .andExpect(status().is4xxClientError());

        var projectId = UUID.randomUUID();
        projects.saveAndFlush(NovelProject.create(projectId, actor.currentUserId(), "提示词文件验证", EntryMode.IDEA));
        var settings = new EffectiveSettings(ModelProvider.DEEPSEEK, "deepseek-flash", "none", 0L);
        var schema = mapper.createObjectNode().put("type", "object")
                .set("properties", mapper.createObjectNode().set("text", mapper.createObjectNode().put("type", "string")));
        when(deepSeek.effectiveSettings()).thenReturn(settings);
        when(deepSeek.request(eq("schema"), anyString(), anyString(), eq(NullNode.getInstance()), eq(3456), eq(settings)))
                .thenReturn(new DeepSeekStructuredOutputClient.ResponseResult("文本结果", null));
        assertThat(models.request(projectId, "OUTLINE", ModelProvider.DEEPSEEK, "默认系统原文", "项目资料保持原样",
                schema, "schema", 3456, CodexSessionPolicy.NEW_THREAD)).isEqualTo("{\"text\":\"文本结果\"}");
        String actual = jdbc.queryForObject("SELECT system_prompt FROM agent_run WHERE project_id = ?", String.class, projectId);
        String actualUser = jdbc.queryForObject("SELECT user_prompt FROM agent_run WHERE project_id = ?", String.class, projectId);
        assertThat(actual).startsWith(AgentPromptDefaults.sessionSystemPrompt("OUTLINE"));
        assertThat(actualUser).contains("默认系统原文", "项目资料保持原样").doesNotContain("提示词配置");
        verify(deepSeek).request("schema", actual, actualUser, NullNode.getInstance(), 3456, settings);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM canon_commit WHERE project_id=?", Integer.class, projectId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM manuscript_version WHERE project_id=?", Integer.class, projectId)).isZero();
    }

    @Test void draftLoopPersistsCheckJudgmentAndDraftVersionsWithoutAcceptingOrWritingCanon() throws Exception {
        var projectId = UUID.randomUUID();
        projects.saveAndFlush(NovelProject.create(projectId, actor.currentUserId(), "自动编辑隔离验证", EntryMode.IDEA));
        var plan = new ChapterContractContent("纸条", "沈秋", "归还纸条", "开学",
                List.of(), List.of("归还"), List.of(), List.of(), "纸条归还",
                List.of(), "对方没有回应", 1000, 3000);
        var basis = new DraftLoopRun.Basis("source-fingerprint", "冻结的校园关系和风格资料",
                new ManuscriptBasis(UUID.randomUUID(), "chapter-fingerprint", plan));
        var context = mock(WritingContextService.Context.class);
        when(context.chapter()).thenReturn(new ChapterPlan(1, "纸条", "沈秋", "归还纸条",
                "归还", "未收到回应", "等待回答", 1000, 3000));
        when(loopContexts.freeze(projectId, 1, ModelProvider.DEEPSEEK)).thenReturn(
                new DraftLoopContext.Source(basis, context, null));
        when(loopContexts.capture(projectId, 1)).thenAnswer(ignored -> new DraftLoopContext.Source(
                basis, context, manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1).orElse(null)));
        var selected = new EffectiveSettings(ModelProvider.DEEPSEEK, "deepseek-flash", "none", 0L);
        when(deepSeek.effectiveSettings()).thenReturn(selected);
        when(deepSeek.request(anyString(), anyString(), anyString(), any(), anyInt(), eq(selected))).thenAnswer(call -> {
            String stage = call.getArgument(0), user = call.getArgument(2);
            var original = new ManuscriptContent("纸条", "她递还纸条。", "归还纸条", List.of());
            var revised = new ManuscriptContent("纸条", "沈秋递还纸条。", "沈秋归还纸条", List.of());
            Object result;
            if (stage.equals("draft_loop_a")) result = new GeneratedManuscript(original);
            else if (stage.equals("draft_loop_b")) result = new DraftCheck("本轮检查",
                    mapper.readTree(user).path("currentDraft").path("body").asText().equals(original.body()) ? List.of(
                            new DraftCheck.Issue("F1", QualityDimension.FLUENCY,
                                    "主体不明", original.body(), "本章视角", "", "", "", "明确动作主体")) : List.of());
            else result = new DraftJudgment(DraftJudgment.Action.REVISED,
                    List.of(new DraftJudgment.Decision("F1", DraftJudgment.Verdict.ACCEPT,
                            "已知人物与计划内动作")), revised, List.of("F1 明确主体，不改变事件"));
            return new DeepSeekStructuredOutputClient.ResponseResult(mapper.writeValueAsString(result), null);
        });
        var response = mvc.perform(post("/api/v1/projects/" + projectId + "/chapters/1/draft-loops")
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"DEEPSEEK\",\"writeFirst\":true,\"maxRounds\":10}"))
                .andExpect(status().isAccepted()).andReturn();
        UUID id = UUID.fromString(mapper.readTree(response.getResponse().getContentAsByteArray()).path("id").asText());
        for (int i = 0; i < 200 && draftLoops.get(projectId, id).active(); i++) Thread.sleep(50);
        var run = draftLoops.get(projectId, id);
        assertThat(run.getStatus()).isEqualTo(DraftLoopRun.Status.STOPPED);
        assertThat(run.getStopReason()).isEqualTo(DraftLoopRun.StopReason.B_CLEAR);
        assertThat(run.getRounds()).hasSize(2);
        assertThat(run.getRounds().getFirst().judgment().decisions().getFirst().reason()).contains("计划内动作");
        assertThat(manuscripts.findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1)).hasSize(2)
                .allMatch(value -> value.getStatus() == ManuscriptStatus.DRAFT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE project_id=? AND status='SUCCEEDED'", Integer.class, projectId)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM canon_commit WHERE project_id=?", Integer.class, projectId)).isZero();
        mvc.perform(get("/api/v1/projects/" + projectId + "/chapters/1/draft-loops"))
                .andExpect(jsonPath("$[0].stopReason").value("B_CLEAR"))
                .andExpect(jsonPath("$[0].rounds[0].judgment.action").value("REVISED"))
                .andExpect(jsonPath("$[0].basis").doesNotExist());
        // Cancel inside the provider wait: even a complete late response must not save another draft or report.
        when(loopContexts.freeze(projectId, 1, ModelProvider.DEEPSEEK)).thenAnswer(ignored -> loopContexts.capture(projectId, 1));
        doAnswer(call -> {
            var running = draftLoops.list(projectId, 1).getFirst();
            draftLoopService.cancel(projectId, running.getId());
            return new DeepSeekStructuredOutputClient.ResponseResult(mapper.writeValueAsString(
                    new DraftCheck("迟到的报告", List.of())), null);
        }).when(deepSeek).request(anyString(), anyString(), anyString(), any(), anyInt(), eq(selected));
        var cancelledResponse = mvc.perform(post("/api/v1/projects/" + projectId + "/chapters/1/draft-loops")
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"DEEPSEEK\",\"writeFirst\":false,\"maxRounds\":10}"))
                .andExpect(status().isAccepted()).andReturn();
        UUID cancelledId = UUID.fromString(mapper.readTree(cancelledResponse.getResponse().getContentAsByteArray()).path("id").asText());
        for (int i = 0; i < 200 && draftLoops.get(projectId, cancelledId).active(); i++) Thread.sleep(50);
        for (int i = 0; i < 200 && jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE project_id=? AND status='RUNNING'",
                Integer.class, projectId) > 0; i++) Thread.sleep(50);
        assertThat(draftLoops.get(projectId, cancelledId).getStatus()).isEqualTo(DraftLoopRun.Status.CANCELLED);
        assertThat(draftLoops.get(projectId, cancelledId).getRounds()).isEmpty();
        assertThat(manuscripts.findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE project_id=? AND status='CANCELLED'", Integer.class, projectId)).isEqualTo(1);
    }

    @AfterAll void cleanup() {
        if (SCHEMA.matches("prompt_app_test_[a-f0-9]{32}")) jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }
}
