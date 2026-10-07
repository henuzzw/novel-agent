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
                .andExpect(jsonPath("$.length()").value(25));
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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM canon_commit", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM manuscript_version", Integer.class)).isZero();
    }

    @AfterAll void cleanup() {
        if (SCHEMA.matches("prompt_app_test_[a-f0-9]{32}")) jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }
    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
}
