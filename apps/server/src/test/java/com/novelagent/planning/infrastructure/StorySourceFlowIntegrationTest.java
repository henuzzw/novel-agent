package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.canon.infrastructure.OutboxPublisher;
import com.novelagent.memory.infrastructure.SemanticEmbeddingBackfill;
import com.novelagent.ingest.domain.ImportPlanningMode;
import com.novelagent.ingest.application.WorkImportService;
import com.novelagent.ingest.application.ImportAnalysisStore;
import com.novelagent.ingest.application.ImportAnalysisRunner;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.SnowflakeStep;
import com.novelagent.planning.infrastructure.CodexAppServerClient;
import com.novelagent.planning.infrastructure.DeepSeekStructuredOutputClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
@EnabledIfEnvironmentVariable(named="NOVEL_SOURCE_DB_TEST", matches="true")
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
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://" + env("DB_HOST","localhost") + ":"
                + env("DB_PORT","5432") + "/" + env("DB_NAME","novel_agent") + "?currentSchema=" + SCHEMA
                + ",public&sslmode=" + env("DB_SSL_MODE","disable"));
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }
    @Test void sourceCreationNamingAnalysisNineStepsAndDirectionsAreSavedWithoutAutoPublication() throws Exception {
        when(deepSeek.effectiveSettings()).thenReturn(new EffectiveSettings(ModelProvider.DEEPSEEK, "fake-model", "none", 0L));
        when(deepSeek.request(anyString(), anyString(), anyString(), eq(NullNode.getInstance()), anyInt(), any()))
                .thenAnswer(call -> new DeepSeekStructuredOutputClient.ResponseResult(switch ((String) call.getArgument(0)) {
                    case "snowflake_book_title" -> "全班都知道她喜欢我";
                    case "import_source_analysis" -> "## @summary\n片段叙述同桌关系，缺少完整经历。\n## @items\n无";
                    case "story_directions" -> directionText();
                    default -> "许言川面对座位与误解，必须用行动澄清关系，否则失去信任；未寄出的信仍等待回答。";
                }, null));
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
        var directions = mvc.perform(post("/api/v1/projects/" + project + "/imports/" + imported.id() + "/actions/prepare-directions")
                .contentType("application/json").content(body.toString())).andExpect(status().isOk())
                .andExpect(jsonPath("$.directions.length()").value(3)).andExpect(jsonPath("$.status").value("DRAFT")).andReturn();
        UUID setId = UUID.fromString(json.readTree(directions.getResponse().getContentAsByteArray()).path("id").asText());
        UUID planId = jdbc.queryForObject("SELECT source_snowflake_id FROM story_direction_set WHERE id=?", UUID.class, setId);
        var steps = json.readTree(jdbc.queryForObject("SELECT steps::text FROM snowflake_planning_run WHERE id=?", String.class, planId));
        assertThat(steps.size()).isEqualTo(9);
        for(var step : SnowflakeStep.values()) assertThat(steps.path(step.name()).asText()).contains("许言川");
        assertThat(imports.get(project, imported.id()).planningStatus()).isEqualTo("DIRECTIONS_READY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM story_bible_version WHERE project_id=?", Integer.class, project)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outline_version WHERE project_id=?", Integer.class, project)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run WHERE project_id=? AND request_snapshot->>'schemaName'='PLAIN_TEXT'", Integer.class, project)).isEqualTo(12);
        assertThat(jdbc.queryForObject("SELECT response_text FROM agent_run WHERE project_id=? AND stage='BOOK_TITLE'", String.class, project)).isEqualTo("全班都知道她喜欢我");
        verifyNoInteractions(codex);
    }
    @Test void invalidSourcesRollBackTheProjectAndNeverCallAModel() throws Exception {
        int before = jdbc.queryForObject("SELECT count(*) FROM novel_project", Integer.class);
        mvc.perform(multipart("/api/v1/projects/from-story")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/projects/from-story").file(new MockMultipartFile("file","source.txt","text/plain","文件".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .param("text","文字")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/projects/from-story").file(new MockMultipartFile("file","source.exe","application/octet-stream",new byte[]{1,2})))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM novel_project",Integer.class)).isEqualTo(before);
        verifyNoInteractions(deepSeek, codex);
    }
    private String directionText() {
        var text = new StringBuilder();
        for(int i=1; i<=3; i++) {
            for(String field : List.of("title","premise","centralConflict","protagonistArc","structure","endingDirection","audienceFit"))
                text.append("## @directions/").append(i).append('/').append(field).append("\n许言川主动承担选择").append(i).append('\n');
            for(String field : List.of("strengths","risks","distinctiveFeatures"))
                text.append("## @directions/").append(i).append('/').append(field).append("\n- 关系变化来自行动\n");
        }
        return text.append("## @questionsForAuthor\n无\n## @changeSummary\n无").toString();
    }
    @AfterAll void cleanup() {
        if(SCHEMA.matches("source_flow_test_[a-f0-9]{32}")) jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }
    private static String env(String key, String fallback) { return System.getenv().getOrDefault(key,fallback); }
}
