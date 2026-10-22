package com.novelagent.modelaccess.infrastructure;

import static com.novelagent.support.TestDatabase.env;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.novelagent.planning.infrastructure.StoryBibleOutputSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.agent.application.GenerationStoppedException;
import com.novelagent.canon.infrastructure.OutboxPublisher;
import com.novelagent.ingest.application.ImportAnalysisRunner;
import com.novelagent.ingest.application.ImportAnalysisStore;
import com.novelagent.ingest.application.WorkImportService;
import com.novelagent.ingest.domain.ImportPlanningMode;
import com.novelagent.memory.infrastructure.SemanticEmbeddingBackfill;
import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.planning.application.SnowflakeStep;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.support.DatabaseTest;
import com.novelagent.support.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Isolated real migrations/API/storage; model substitutes never consume provider credits. */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false",
        "logging.file.name=target/source-flow-integration.log"})
@AutoConfigureMockMvc
@DatabaseTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StorySourceFlowIntegrationTest {
    private static final String SCHEMA = "source_flow_test_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WorkImportService imports;
    @Autowired private ImportAnalysisStore analyses;
    @Autowired private ImportAnalysisRunner runner;
    @MockitoBean private DeepSeekStructuredOutputClient deepSeek;
    @MockitoBean private CodexAppServerClient codex;
    @MockitoBean private OutboxPublisher outbox;
    @MockitoBean private SemanticEmbeddingBackfill embeddings;

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> TestDatabase.url() + "?currentSchema=" + SCHEMA
                + ",public&sslmode=" + env("DB_SSL_MODE","disable"));
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }
    @Test void sourceCreationResumeConfirmationAndOutlineUseSnowflakeWithoutDirections() throws Exception {
        var stopCharacters = new AtomicBoolean(true);
        when(deepSeek.effectiveSettings()).thenReturn(new EffectiveSettings(ModelProvider.DEEPSEEK, "fake-model", "none", 0L));
        when(deepSeek.request(anyString(), anyString(), anyString(), eq(NullNode.getInstance()), anyInt(), any()))
                .thenAnswer(call -> {
                    if (call.getArgument(0).equals("snowflake_character_materials") && stopCharacters.getAndSet(false)) {
                        throw new GenerationStoppedException();
                    }
                    return new DeepSeekStructuredOutputClient.ResponseResult(switch ((String) call.getArgument(0)) {
                    case "snowflake_book_title" -> "全班都知道她喜欢我";
                    case "import_source_analysis" -> "## @summary\n片段叙述同桌关系，缺少完整经历。\n## @items\n无"
                            + "\n## @worldAssessment/worldMode\nREALISTIC\n## @worldAssessment/reason\n没有架空依据。"
                            + "\n## @worldAssessment/evidence\n无";
                    case "snowflake_character_materials" -> materialText();
                    case "imported_outline" -> outlineText();
                    default -> "许言川面对座位与误解，必须用行动澄清关系，否则失去信任；未寄出的信仍等待回答。";
                }, null);
                });
        var created = mvc.perform(multipart("/api/v1/projects/from-story").param("text", "她将纸条递还，他第一次没能用笑话带过去。"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("待生成书名"))
                .andExpect(jsonPath("$.creativeStrategy").value("FANQIE_GRIPPING")).andReturn();
        UUID project = UUID.fromString(json.readTree(created.getResponse().getContentAsByteArray()).path("id").asText());
        var imported = imports.list(project).getFirst();
        assertThat(imported.chapters().getFirst().content()).contains("纸条");
        var report = analyses.create(project, imported.id(), new ImportAnalysisStore.Create(UUID.randomUUID(), ModelProvider.DEEPSEEK));
        report = runner.next(project, imported.id(), report.report().id(), report.report().version());
        assertThat(report.report().status()).isEqualTo("REVIEW");
        assertThat(report.report().content().summaries()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT name FROM novel_project WHERE id=?", String.class, project)).isEqualTo("全班都知道她喜欢我");
        report = analyses.confirm(project, imported.id(), report.report().id(), new ImportAnalysisStore.Confirm(
                report.report().version(), true, ImportPlanningMode.ADAPT_SOURCE, List.of()));
        var body = json.createObjectNode().put("provider", "DEEPSEEK").put("mode", "ADAPT_SOURCE")
                .put("analysisId", report.report().id().toString()).put("analysisVersion", report.report().version())
                .put("targetWords",50000).put("expandScenes",true);
        mvc.perform(post("/api/v1/projects/" + project + "/imports/" + imported.id() + "/actions/prepare-snowflake")
                .contentType("application/json").content(body.toString())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENERATION_CANCELLED"));
        UUID stoppedId = jdbc.queryForObject("SELECT id FROM snowflake_planning_run WHERE project_id=?", UUID.class, project);
        String prefix = jdbc.queryForObject("SELECT steps::text FROM snowflake_planning_run WHERE id=?", String.class, stoppedId);
        assertThat(json.readTree(prefix).size()).isEqualTo(6);
        assertThat(imports.get(project, imported.id()).planningStatus()).isEqualTo("CANCELLED");
        mvc.perform(get("/api/v1/projects/" + project + "/snowflake-plans/latest"))
                .andExpect(jsonPath("$.sourceImportId").value(imported.id().toString()));
        var resumed = mvc.perform(post("/api/v1/projects/" + project + "/imports/" + imported.id() + "/actions/resume-snowflake")
                .contentType("application/json").content(json.createObjectNode().put("planId", stoppedId.toString()).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps.SCENE_LIST").isNotEmpty()).andExpect(jsonPath("$.status").value("SUCCEEDED")).andReturn();
        UUID planId = UUID.fromString(json.readTree(resumed.getResponse().getContentAsByteArray()).path("id").asText());
        assertThat(planId).isEqualTo(stoppedId);
        var steps = json.readTree(jdbc.queryForObject("SELECT steps::text FROM snowflake_planning_run WHERE id=?", String.class, planId));
        assertThat(steps.size()).isEqualTo(9);
        for(var step : SnowflakeStep.values()) assertThat(steps.path(step.name()).asText()).contains("许言川");
        json.readTree(prefix).fields().forEachRemaining(entry -> assertThat(steps.get(entry.getKey())).isEqualTo(entry.getValue()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM snowflake_planning_run WHERE project_id=?", Integer.class, project)).isEqualTo(1);
        mvc.perform(post("/api/v1/projects/" + project + "/imports/" + imported.id() + "/actions/resume-snowflake")
                .contentType("application/json").content(json.createObjectNode().put("planId", stoppedId.toString()).toString()))
                .andExpect(status().isBadRequest());
        assertThat(imports.get(project, imported.id()).planningStatus()).isEqualTo("PLAN_READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM story_bible_version WHERE project_id=?", Integer.class, project)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outline_version WHERE project_id=?", Integer.class, project)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE project_id=? AND request_snapshot->>'schemaName'='PLAIN_TEXT'", Integer.class, project)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT response_text FROM agent_run WHERE project_id=? AND stage='BOOK_TITLE'", String.class, project)).isEqualTo("全班都知道她喜欢我");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM story_direction_set WHERE project_id=?", Integer.class, project)).isZero();
        int callsBeforeConfirmation = jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE project_id=?", Integer.class, project);
        for (int i = 0; i < 2; i++) mvc.perform(post("/api/v1/projects/" + project + "/snowflake-plans/" + planId + "/actions/confirm"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceSnowflakeId").value(planId.toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE project_id=?", Integer.class, project)).isEqualTo(callsBeforeConfirmation);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM story_bible_version WHERE project_id=?", Integer.class, project)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM character_profile WHERE project_id=?", Integer.class, project)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM planning_relationship WHERE project_id=?", Integer.class, project)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reader_experience_plan WHERE project_id=?", Integer.class, project)).isEqualTo(1);
        mvc.perform(post("/api/v1/projects/" + project + "/outlines/actions/generate").contentType("application/json")
                .content("{\"provider\":\"DEEPSEEK\",\"mode\":\"REGENERATE\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.content.arcs[0].chapters[0].content").value("许言川主动澄清误会，承担选择后果。"));
        mvc.perform(get("/api/v1/projects/" + project + "/story-directions/latest")).andExpect(status().isNotFound());
        verifyNoInteractions(codex);
    }
    @Test void unreadableOutputIsFailedAndExplicitResumeRepairsTheSavedDependencySuffix() throws Exception {
        var refuse = new AtomicBoolean(true);
        when(deepSeek.effectiveSettings()).thenReturn(new EffectiveSettings(ModelProvider.DEEPSEEK, "fake-model", "none", 0L));
        when(deepSeek.request(anyString(), anyString(), anyString(), eq(NullNode.getInstance()), anyInt(), any()))
                .thenAnswer(call -> new DeepSeekStructuredOutputClient.ResponseResult(switch ((String) call.getArgument(0)) {
                    case "import_source_analysis" -> "## @summary\n片段描述人物选择。\n## @items\n无"
                            + "\n## @worldAssessment/worldMode\nREALISTIC\n## @worldAssessment/reason\n现实校园。\n## @worldAssessment/evidence\n无";
                    case "snowflake_core" -> refuse.getAndSet(false) ? "[NOVEL_INPUT_UNAVAILABLE] 本轮资料无法读取"
                            : "许言川面对误会，选择亲口解释，争取同学的信任。";
                    case "snowflake_character_materials" -> materialText();
                    default -> "许言川面对误会，选择亲口解释，争取同学的信任。";
                }, null));
        var created = mvc.perform(multipart("/api/v1/projects/from-story").param("name", "资料恢复测试")
                .param("text", "她将纸条递还，他第一次没能用笑话带过去。"))
                .andExpect(status().isCreated()).andReturn();
        UUID project = UUID.fromString(json.readTree(created.getResponse().getContentAsByteArray()).path("id").asText());
        var imported = imports.list(project).getFirst();
        var analysis = analyses.create(project, imported.id(), new ImportAnalysisStore.Create(UUID.randomUUID(), ModelProvider.DEEPSEEK));
        analysis = runner.next(project, imported.id(), analysis.report().id(), analysis.report().version());
        analysis = analyses.confirm(project, imported.id(), analysis.report().id(), new ImportAnalysisStore.Confirm(
                analysis.report().version(), true, ImportPlanningMode.ADAPT_SOURCE, List.of()));
        var body = json.createObjectNode().put("provider", "DEEPSEEK").put("mode", "ADAPT_SOURCE")
                .put("analysisId", analysis.report().id().toString()).put("analysisVersion", analysis.report().version())
                .put("targetWords", 50000).put("expandScenes", false);
        mvc.perform(post("/api/v1/projects/" + project + "/imports/" + imported.id() + "/actions/prepare-snowflake")
                .contentType("application/json").content(body.toString())).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("MODEL_INPUT_UNAVAILABLE"));
        UUID plan = jdbc.queryForObject("SELECT id FROM snowflake_planning_run WHERE project_id=?", UUID.class, project);
        assertThat(jdbc.queryForObject("SELECT steps::text FROM snowflake_planning_run WHERE id=?", String.class, plan)).isEqualTo("{}");
        String response = jdbc.queryForObject("SELECT response_text FROM agent_run WHERE project_id=? AND stage='SNOWFLAKE_PLANNING'", String.class, project);
        assertThat(response).startsWith("[NOVEL_INPUT_UNAVAILABLE]");
        assertThat(jdbc.queryForObject("SELECT error_category FROM agent_run WHERE project_id=? AND stage='SNOWFLAKE_PLANNING'", String.class, project))
                .isEqualTo("INPUT_UNAVAILABLE");
        var old = json.createObjectNode().put("CORE", "当前环境没有可用的本地文件读取工具，无法读取指定完整来源。")
                .put("SYNOPSIS", "依赖无效来源的旧梗概");
        jdbc.update("UPDATE snowflake_planning_run SET steps=CAST(? AS jsonb), core=? WHERE id=?", old.toString(), old.path("CORE").asText(), plan);
        mvc.perform(get("/api/v1/projects/" + project + "/snowflake-plans/latest"))
                .andExpect(jsonPath("$.invalidSteps.CORE").isNotEmpty()).andExpect(jsonPath("$.invalidSteps.SYNOPSIS").isNotEmpty());
        mvc.perform(post("/api/v1/projects/" + project + "/imports/" + imported.id() + "/actions/resume-snowflake")
                .contentType("application/json").content(json.createObjectNode().put("planId", plan.toString()).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.invalidSteps.CORE").doesNotExist())
                .andExpect(jsonPath("$.steps.CORE").value("许言川面对误会，选择亲口解释，争取同学的信任。"));
        String saved = jdbc.queryForObject("SELECT steps::text FROM snowflake_planning_run WHERE id=?", String.class, plan);
        assertThat(json.readTree(saved).size()).isEqualTo(8);
        assertThat(saved).doesNotContain("读取工具", "旧梗概");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE project_id=? AND status='FAILED' AND response_text=?", Integer.class, project, response))
                .isEqualTo(1);
        verifyNoInteractions(codex);
    }

    @Test void invalidSourcesRollBackTheProjectAndNeverCallAModel() throws Exception {
        int before = jdbc.queryForObject("SELECT count(*) FROM novel_project", Integer.class);
        mvc.perform(multipart("/api/v1/projects/from-story")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/projects/from-story").file(new MockMultipartFile("file","source.txt","text/plain","文件".getBytes(StandardCharsets.UTF_8)))
                .param("text","文字")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/projects/from-story").file(new MockMultipartFile("file","source.exe","application/octet-stream",new byte[]{1,2})))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM novel_project",Integer.class)).isEqualTo(before);
        verifyNoInteractions(deepSeek, codex);
    }
    private String materialText() throws Exception {
        var content = new StoryBibleContent("许言川主动回应", "成长", "校园", List.of("不得替别人选择"),
                "许言川", "学会承担", List.of(), List.of("许言川与同学尚有误解"), "误会", "信任", "清楚", "共同选择", List.of(), List.of());
        var node = (ObjectNode) json.valueToTree(content);
        node.remove("developmentNotes");
        var properties = new StoryBibleOutputSchema(json).value().at("/properties/content/properties/characterBlueprints/items/properties");
        var character = node.putArray("characterBlueprints").addObject();
        properties.fields().forEachRemaining(field -> {
            if (field.getValue().path("type").asText().equals("array")) character.putArray(field.getKey());
            else character.put(field.getKey(), "");
        });
        character.put("name", "许言川").put("role", "PROTAGONIST").put("identity", "学生").put("coreDesire", "澄清误解")
                .put("background", "许言川曾用玩笑回避选择");
        node.putArray("readerExperiencePlans").addObject().put("key", "letter").put("kind", "FORESHADOW")
                .put("title", "未寄出的信").put("promise", "许言川需要回应").put("setup", "收到旧信")
                .put("payoff", "主动回应").put("aftermath", "关系改变").putNull("plannedChapter");
        var text = new StringBuilder();
        appendSections(node, "materials", text);
        return text.toString();
    }

    private String outlineText() {
        var text = new StringBuilder();
        for (String field : List.of("title", "premise", "structureSummary", "pacingStrategy"))
            text.append("## @content/").append(field).append("\n许言川主动澄清误解\n");
        text.append("## @content/suggestedMinWords\n45000\n## @content/suggestedMaxWords\n55000\n");
        text.append("## @content/arcs/1/ordinal\n1\n");
        for (String field : List.of("title", "objective", "mainConflict", "turningPoint", "outcome"))
            text.append("## @content/arcs/1/").append(field).append("\n许言川主动承担选择\n");
        return text.append("## @content/arcs/1/suggestedMinWords\n45000\n## @content/arcs/1/suggestedMaxWords\n55000\n")
                .append("## @content/arcs/1/chapters/1/number\n1\n## @content/arcs/1/chapters/1/title\n回应\n")
                .append("## @content/arcs/1/chapters/1/content\n许言川主动澄清误会，承担选择后果。\n")
                .append("## @content/arcs/1/chapters/1/status\nPLANNED\n## @content/readerExperiencePlans\n无\n## @changeSummary\n无\n").toString();
    }

    private static void appendSections(JsonNode node, String path, StringBuilder text) {
        if (node.isObject()) node.fields().forEachRemaining(entry -> appendSections(entry.getValue(), path + "/" + entry.getKey(), text));
        else if (node.isArray() && !node.isEmpty() && node.get(0).isObject()) {
            for (int i = 0; i < node.size(); i++) appendSections(node.get(i), path + "/" + (i + 1), text);
        } else {
            text.append("## @").append(path).append('\n');
            if (node.isArray()) {
                if (node.isEmpty()) text.append("无\n");
                else node.forEach(item -> text.append("- ").append(item.asText()).append('\n'));
            } else text.append(node.isNull() ? "null" : node.asText()).append('\n');
        }
    }
    @AfterAll void cleanup() {
        if(SCHEMA.matches("source_flow_test_[a-f0-9]{32}")) jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }
}
