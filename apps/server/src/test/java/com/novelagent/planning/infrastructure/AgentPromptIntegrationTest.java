package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.canon.infrastructure.OutboxPublisher;
import com.novelagent.memory.infrastructure.SemanticEmbeddingBackfill;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.CodexAppServerClient;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.DeepSeekStructuredOutputClient;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
@EnabledIfEnvironmentVariable(named = "NOVEL_PROMPT_DB_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentPromptIntegrationTest {
    private static final String SCHEMA = "prompt_app_test_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private StructuredModelGateway models;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private NovelProjectRepository projects;
    @Autowired private CurrentActorProvider actor;
    @Autowired private StoryDirectionSetRepository directions;
    @Autowired private com.novelagent.writing.application.DraftLoopStore draftLoops;
    @Autowired private com.novelagent.writing.application.DraftLoopService draftLoopService;
    @Autowired private com.novelagent.writing.infrastructure.ManuscriptVersionRepository manuscripts;
    @MockitoBean private com.novelagent.writing.application.DraftLoopContext loopContexts;
    @MockitoBean private CodexAppServerClient codex;
    @MockitoBean private DeepSeekStructuredOutputClient deepSeek;
    @MockitoBean private OutboxPublisher outbox;
    @MockitoBean private SemanticEmbeddingBackfill embeddings;

    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry properties) {
        String url = "jdbc:postgresql://" + env("DB_HOST", "localhost") + ":" + env("DB_PORT", "5432")
                + "/" + env("DB_NAME", "novel_agent") + "?currentSchema=" + SCHEMA + ",public&sslmode=" + env("DB_SSL_MODE", "disable");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Test void savesThroughTheApiAppliesToARealRecordedCallAndResetsWithoutWritingCanon() throws Exception {
        mvc.perform(get("/api/v1/settings/prompts")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(24));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_agent_prompt", Integer.class)).isZero();
        String payload = "{\"systemPrompt\":\"集成验证的阶段角色\",\"guidance\":\"紧扣当前矛盾\",\"version\":0}";
        mvc.perform(put("/api/v1/settings/prompts/OUTLINE").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/v1/settings/prompts/OUTLINE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.systemPrompt").value("集成验证的阶段角色"));
        mvc.perform(put("/api/v1/settings/prompts/OUTLINE").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict());

        var projectId = UUID.randomUUID();
        projects.saveAndFlush(NovelProject.create(projectId, actor.currentUserId(), "提示词隔离验证", EntryMode.IDEA));
        var settings = new EffectiveSettings(ModelProvider.DEEPSEEK, "deepseek-flash", "none", 0L);
        var schema = mapper.createObjectNode().put("type", "object");
        when(deepSeek.effectiveSettings()).thenReturn(settings);
        when(deepSeek.request(eq("schema"), anyString(), eq("项目资料保持原样"), eq(schema), eq(3456), eq(settings)))
                .thenReturn(new DeepSeekStructuredOutputClient.ResponseResult("{\"ok\":true}", null));
        assertThat(models.request(projectId, "OUTLINE", ModelProvider.DEEPSEEK, "默认系统原文", "项目资料保持原样",
                schema, "schema", 3456, CodexSessionPolicy.NEW_THREAD)).isEqualTo("{\"ok\":true}");
        String actual = jdbc.queryForObject("SELECT system_prompt FROM agent_run WHERE project_id = ?", String.class, projectId);
        assertThat(actual).startsWith("集成验证的阶段角色").contains("紧扣当前矛盾", "OUTLINE · 版本 1", "不提交正史");
        verify(deepSeek).request("schema", actual, "项目资料保持原样", schema, 3456, settings);
        mvc.perform(post("/api/v1/settings/prompts/OUTLINE/reset").contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.customized").value(false))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(get("/api/v1/settings/prompts/OUTLINE/history")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].operation").value("RESET"))
                .andExpect(jsonPath("$[1].systemPrompt").value("集成验证的阶段角色"));
        assertThat(jdbc.queryForObject("SELECT system_prompt FROM agent_run WHERE project_id = ?", String.class, projectId)).isEqualTo(actual);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM canon_commit WHERE project_id=?", Integer.class, projectId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM manuscript_version WHERE project_id=?", Integer.class, projectId)).isZero();
    }

    @Test void snowflakeProseIsPersistedAndReadableWithoutPublishingAnything() throws Exception {
        var projectId = UUID.randomUUID();
        projects.saveAndFlush(NovelProject.create(projectId, actor.currentUserId(), "雪花隔离验证", EntryMode.IDEA));
        var selected = new EffectiveSettings(ModelProvider.DEEPSEEK, "deepseek-flash", "none", 0L);
        when(deepSeek.effectiveSettings()).thenReturn(selected);
        when(deepSeek.request(anyString(), anyString(), anyString(), any(), anyInt(), eq(selected)))
                .thenAnswer(call -> new DeepSeekStructuredOutputClient.ResponseResult(
                        mapper.createObjectNode().put("text", "自由文本：" + call.getArgument(0)).toString(), null));
        var service = new com.novelagent.planning.application.SnowflakePlanningService(
                new com.novelagent.project.application.ProjectAccessService(projects, actor),
                new SnowflakePlanStore(jdbc), new FreeTextPlanningRequest(models, mapper),
                new com.novelagent.planning.application.CharacterDesignService(models, mapper,
                        new StoryBibleOutputSchema(mapper), new FreeTextPlanningRequest(models, mapper)));
        var result = service.generate(projectId, ModelProvider.DEEPSEEK,
                mapper.createObjectNode().put("mode", "CONTINUE_MANUSCRIPT").put("authorInstruction", "只设计未来"));
        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.context()).contains("snowflake_core", "snowflake_characters", "snowflake_world", "snowflake_plot");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE project_id = ? AND status = 'SUCCEEDED'",
                Integer.class, projectId)).isEqualTo(4);
        mvc.perform(get("/api/v1/projects/" + projectId + "/snowflake-plans/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("CONTINUE_MANUSCRIPT"))
                .andExpect(jsonPath("$.plot").value("自由文本：snowflake_plot"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM story_bible_version WHERE project_id = ?", Integer.class, projectId)).isZero();

        when(deepSeek.request(eq("snowflake_world"), anyString(), anyString(), any(), anyInt(), eq(selected)))
                .thenThrow(new IllegalStateException("模拟供应商超时"));
        assertThatThrownBy(() -> service.generate(projectId, ModelProvider.DEEPSEEK,
                mapper.createObjectNode().put("mode", "ADAPT_SOURCE"))).hasMessageContaining("模拟供应商超时");
        var failed = service.latest(projectId).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.activeStage()).isEqualTo("WORLD");
        assertThat(failed.core()).isNotBlank();
        assertThat(failed.characters()).isNotBlank();
        assertThat(failed.world()).isNull();
        assertThat(failed.plot()).isNull();
        mvc.perform(get("/api/v1/projects/" + projectId + "/snowflake-plans/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.errorMessage").value("模拟供应商超时"));
        var foreignId = UUID.randomUUID();
        projects.saveAndFlush(NovelProject.create(foreignId, UUID.randomUUID(), "他人项目", EntryMode.IDEA));
        mvc.perform(get("/api/v1/projects/" + foreignId + "/snowflake-plans/latest")).andExpect(status().isNotFound());
    }

    @Test void newBibleApiRunsTheFourStagesThenAssemblesOneDraftAndKeepsNotesForOutline() throws Exception {
        UUID projectId = UUID.randomUUID();
        projects.saveAndFlush(NovelProject.create(projectId, actor.currentUserId(), "渐进圣经完整链路", EntryMode.IDEA));
        var snapshot = new com.novelagent.planning.domain.CreativeIntentSnapshot("校园关系", java.util.List.of("校园"),
                "青年", "江澈", "回避选择", java.util.List.of("克制"), 50000, "承担责任",
                java.util.List.of("保留选座"), java.util.List.of(), java.util.List.of(), 0L);
        var candidate = new com.novelagent.planning.domain.StoryDirectionCandidate(UUID.randomUUID(), "方向",
                "主角试图维持平静却必须作出选择", "回避选择", "主动承担", "三幕", "承担责任", "青年",
                java.util.List.of(), java.util.List.of(), java.util.List.of());
        var other = new com.novelagent.planning.domain.StoryDirectionCandidate(UUID.randomUUID(), "备选",
                "另外方向", "冲突", "弧光", "结构", "结局", "青年", java.util.List.of(), java.util.List.of(), java.util.List.of());
        var direction = com.novelagent.planning.domain.StoryDirectionSet.create(UUID.randomUUID(), projectId, 1,
                "TEST", null, snapshot, java.util.List.of(candidate, other), java.util.List.of());
        direction.select(candidate.id());
        directions.saveAndFlush(direction);
        var content = com.novelagent.planning.domain.CharacterBlueprintFixtures.bible(
                java.util.List.of(com.novelagent.planning.domain.CharacterBlueprintFixtures.character("江澈")));
        var bibleOutput = mapper.createObjectNode();
        bibleOutput.set("content", mapper.valueToTree(content));
        bibleOutput.putArray("changeSummary");
        var selected = new EffectiveSettings(ModelProvider.DEEPSEEK, "deepseek-flash", "none", 0L);
        when(deepSeek.effectiveSettings()).thenReturn(selected);
        when(deepSeek.request(anyString(), anyString(), anyString(), any(), anyInt(), eq(selected)))
                .thenAnswer(call -> new DeepSeekStructuredOutputClient.ResponseResult(
                        "story_bible".equals(call.getArgument(0)) ? bibleOutput.toString()
                                : mapper.createObjectNode().put("text", "原始自由文本：" + call.getArgument(0)).toString(), null));
        var response = mvc.perform(post("/api/v1/projects/" + projectId + "/story-bibles/actions/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.createObjectNode().put("provider", "DEEPSEEK").put("mode", "REGENERATE")
                        .put("instruction", "保留选座开场").toString()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.authorInstruction").value("保留选座开场")).andReturn();
        var saved = mapper.readTree(response.getResponse().getContentAsByteArray());
        String notes = saved.path("content").path("developmentNotes").asText();
        assertThat(notes).contains("snowflake_core", "snowflake_characters", "snowflake_world", "snowflake_plot");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE project_id = ? AND status = 'SUCCEEDED'",
                Integer.class, projectId)).isEqualTo(5);
        assertThat(projects.findById(projectId).orElseThrow().getCurrentBibleVersionId()).isNull();
        var restored = mapper.treeToValue(saved.path("content"), com.novelagent.planning.domain.StoryBibleContent.class);
        String outlinePrompt = new OutlineModelPromptFactory(mapper).userPrompt(restored,
                new com.novelagent.planning.domain.OutlineWordBudgetPolicy().plan(50000), null, "按因果展开");
        assertThat(outlinePrompt).contains(mapper.writeValueAsString(notes), "developmentNotes");
    }

    @Test void draftLoopPersistsCheckJudgmentAndDraftVersionsWithoutAcceptingOrWritingCanon() throws Exception {
        var projectId = UUID.randomUUID();
        projects.saveAndFlush(NovelProject.create(projectId, actor.currentUserId(), "自动编辑隔离验证", EntryMode.IDEA));
        var plan = new com.novelagent.writing.domain.ChapterContractContent("纸条", "沈秋", "归还纸条", "开学",
                java.util.List.of(), java.util.List.of("归还"), java.util.List.of(), java.util.List.of(), "纸条归还",
                java.util.List.of(), "对方没有回应", 1000, 3000);
        var basis = new com.novelagent.writing.domain.DraftLoopRun.Basis("source-fingerprint", "冻结的校园关系和风格资料",
                new com.novelagent.writing.domain.ManuscriptBasis(UUID.randomUUID(), "chapter-fingerprint", plan));
        var context = mock(com.novelagent.writing.application.WritingContextService.Context.class);
        when(context.chapter()).thenReturn(new com.novelagent.planning.domain.ChapterPlan(1, "纸条", "沈秋", "归还纸条",
                "归还", "未收到回应", "等待回答", 1000, 3000));
        when(loopContexts.freeze(projectId, 1, ModelProvider.DEEPSEEK)).thenReturn(
                new com.novelagent.writing.application.DraftLoopContext.Source(basis, context, null));
        when(loopContexts.capture(projectId, 1)).thenAnswer(ignored -> new com.novelagent.writing.application.DraftLoopContext.Source(
                basis, context, manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1).orElse(null)));
        var selected = new EffectiveSettings(ModelProvider.DEEPSEEK, "deepseek-flash", "none", 0L);
        when(deepSeek.effectiveSettings()).thenReturn(selected);
        when(deepSeek.request(anyString(), anyString(), anyString(), any(), anyInt(), eq(selected))).thenAnswer(call -> {
            String stage = call.getArgument(0), user = call.getArgument(2);
            var original = new com.novelagent.writing.domain.ManuscriptContent("纸条", "她递还纸条。", "归还纸条", java.util.List.of());
            var revised = new com.novelagent.writing.domain.ManuscriptContent("纸条", "沈秋递还纸条。", "沈秋归还纸条", java.util.List.of());
            Object result;
            if (stage.equals("draft_loop_a")) result = new com.novelagent.writing.application.GeneratedManuscript(original);
            else if (stage.equals("draft_loop_b")) result = new com.novelagent.writing.domain.DraftCheck("本轮检查",
                    mapper.readTree(user).path("currentDraft").path("body").asText().equals(original.body()) ? java.util.List.of(
                            new com.novelagent.writing.domain.DraftCheck.Issue("F1", com.novelagent.writing.domain.QualityDimension.FLUENCY,
                                    "主体不明", original.body(), "本章视角", "", "", "", "明确动作主体")) : java.util.List.of());
            else result = new com.novelagent.writing.domain.DraftJudgment(com.novelagent.writing.domain.DraftJudgment.Action.REVISED,
                    java.util.List.of(new com.novelagent.writing.domain.DraftJudgment.Decision("F1", com.novelagent.writing.domain.DraftJudgment.Verdict.ACCEPT,
                            "已知人物与计划内动作")), revised, java.util.List.of("F1 明确主体，不改变事件"));
            return new DeepSeekStructuredOutputClient.ResponseResult(mapper.writeValueAsString(result), null);
        });
        var response = mvc.perform(post("/api/v1/projects/" + projectId + "/chapters/1/draft-loops")
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"DEEPSEEK\",\"writeFirst\":true,\"maxRounds\":10}"))
                .andExpect(status().isAccepted()).andReturn();
        UUID id = UUID.fromString(mapper.readTree(response.getResponse().getContentAsByteArray()).path("id").asText());
        for (int i = 0; i < 200 && draftLoops.get(projectId, id).active(); i++) Thread.sleep(50);
        var run = draftLoops.get(projectId, id);
        assertThat(run.getStatus()).isEqualTo(com.novelagent.writing.domain.DraftLoopRun.Status.STOPPED);
        assertThat(run.getStopReason()).isEqualTo(com.novelagent.writing.domain.DraftLoopRun.StopReason.B_CLEAR);
        assertThat(run.getRounds()).hasSize(2);
        assertThat(run.getRounds().getFirst().judgment().decisions().getFirst().reason()).contains("计划内动作");
        assertThat(manuscripts.findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1)).hasSize(2)
                .allMatch(value -> value.getStatus() == com.novelagent.writing.domain.ManuscriptStatus.DRAFT);
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
                    new com.novelagent.writing.domain.DraftCheck("迟到的报告", java.util.List.of())), null);
        }).when(deepSeek).request(anyString(), anyString(), anyString(), any(), anyInt(), eq(selected));
        var cancelledResponse = mvc.perform(post("/api/v1/projects/" + projectId + "/chapters/1/draft-loops")
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"DEEPSEEK\",\"writeFirst\":false,\"maxRounds\":10}"))
                .andExpect(status().isAccepted()).andReturn();
        UUID cancelledId = UUID.fromString(mapper.readTree(cancelledResponse.getResponse().getContentAsByteArray()).path("id").asText());
        for (int i = 0; i < 200 && draftLoops.get(projectId, cancelledId).active(); i++) Thread.sleep(50);
        for (int i = 0; i < 200 && jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE project_id=? AND status='RUNNING'",
                Integer.class, projectId) > 0; i++) Thread.sleep(50);
        assertThat(draftLoops.get(projectId, cancelledId).getStatus()).isEqualTo(com.novelagent.writing.domain.DraftLoopRun.Status.CANCELLED);
        assertThat(draftLoops.get(projectId, cancelledId).getRounds()).isEmpty();
        assertThat(manuscripts.findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE project_id=? AND status='CANCELLED'", Integer.class, projectId)).isEqualTo(1);
    }

    @AfterAll void cleanup() {
        if (SCHEMA.matches("prompt_app_test_[a-f0-9]{32}")) jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }
    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
}
