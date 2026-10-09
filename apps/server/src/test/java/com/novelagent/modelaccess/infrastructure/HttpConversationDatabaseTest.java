package com.novelagent.modelaccess.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.modelaccess.application.ChatGptOAuthService;
import com.novelagent.modelaccess.application.ChatGptTransportService;
import com.novelagent.project.application.CurrentActorProvider;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "NOVEL_MODELACCESS_DB_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpConversationDatabaseTest {
    private final String schema = "modelaccess_test_" + UUID.randomUUID().toString().replace("-", "");
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID();
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private HttpConversationStore store;
    private DataSourceTransactionManager manager;
    @BeforeAll void setup() throws Exception {
        var source = new DriverManagerDataSource("jdbc:postgresql://" + env("DB_HOST", "localhost") + ":" + env("DB_PORT", "5432")
                + "/" + env("DB_NAME", "novel_agent") + "?currentSchema=" + schema + "&sslmode=" + env("DB_SSL_MODE", "disable"),
                env("DB_USERNAME", "novel_app"), env("DB_PASSWORD", "change-me"));
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("CREATE TABLE novel_project(id uuid PRIMARY KEY, owner_id uuid NOT NULL)");
        jdbc.execute("CREATE TABLE agent_run(id uuid PRIMARY KEY, project_id uuid, status varchar(32), started_at timestamptz DEFAULT now())");
        try (var stream = getClass().getResourceAsStream("/db/migration/V056__chatgpt_direct_transport.sql")) {
            jdbc.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        jdbc.update("INSERT INTO novel_project VALUES (?, ?)", project, owner);
        store = new HttpConversationStore(jdbc, json, new CurrentActorProvider(owner), manager);
    }
    @Test void leasesProtectOrderingHistoryRevisionAndOwnership() {
        var first = store.claim(project, "STORY_PLANNING", "account-one", "v1", false);
        assertThatThrownBy(() -> store.claim(project, "STORY_PLANNING", "account-one", "v1", false)).hasMessageContaining("正在生成");
        var history = json.createArrayNode(); history.addObject().put("role", "assistant").put("content", "bible text");
        store.complete(first, history);
        var outline = store.claim(project, "STORY_PLANNING", "account-one", "v1", false);
        assertThat(outline.conversation()).isEqualTo(first.conversation()); assertThat(outline.history()).hasSize(1);
        store.release(outline);
        var revised = store.claim(project, "STORY_PLANNING", "account-one", "v2", false);
        assertThat(revised.history()).isEmpty(); assertThat(revised.conversation()).isNotEqualTo(first.conversation()); store.release(revised);
        var different = store.claim(project, "STORY_PLANNING", "account-two", "v2", false);
        assertThat(different.conversation()).isNotEqualTo(revised.conversation()); store.release(different);
        var intruder = new HttpConversationStore(jdbc, json, new CurrentActorProvider(UUID.randomUUID()), manager);
        assertThatThrownBy(() -> intruder.claim(project, "OTHER", "account", "v1", false)).hasMessageContaining("不属于当前用户");
        assertThatThrownBy(() -> store.complete(first, history)).hasMessageContaining("被替换");
    }
    @Test void transportChangesAreExplicitVersionedAndBlockedWhileGenerating() {
        var auth = mock(ChatGptOAuthService.class);
        when(auth.authorization()).thenReturn(new PrivateOAuthStore.Credentials("oaiapp_test", "subject", null, null, "test", "refresh", Long.MAX_VALUE, "chatgpt.tokens.use.direct"));
        var service = new ChatGptTransportService(jdbc, new CurrentActorProvider(owner), auth);
        var tx = new TransactionTemplate(manager);
        assertThat(service.get().transport()).isEqualTo(ChatGptTransportService.Transport.APP_SERVER);
        var direct = tx.execute(status -> service.update(new ChatGptTransportService.Choice(ChatGptTransportService.Transport.SIWC_HTTP, 0)));
        assertThat(direct.version()).isEqualTo(1);
        assertThatThrownBy(() -> tx.execute(status -> service.update(new ChatGptTransportService.Choice(ChatGptTransportService.Transport.APP_SERVER, 0))))
                .isInstanceOf(com.novelagent.project.application.ResourceVersionConflictException.class);
        var legacy = UUID.randomUUID();
        jdbc.update("INSERT INTO agent_run(id,project_id,status,started_at) VALUES (?, ?, 'RUNNING', '2000-01-01')", legacy, project);
        service.requireIdle();
        var lease = store.claim(project, "ACCOUNT_GUARD", "account", "v1", false);
        try { assertThatThrownBy(service::requireIdle).isInstanceOf(IllegalStateException.class); }
        finally { store.release(lease); }
        var active = UUID.randomUUID(); jdbc.update("INSERT INTO agent_run(id,project_id,status) VALUES (?, ?, 'RUNNING')", active, project);
        try {
            assertThatThrownBy(() -> tx.execute(status -> service.update(new ChatGptTransportService.Choice(ChatGptTransportService.Transport.APP_SERVER, 1))))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("模型任务");
        } finally { jdbc.update("DELETE FROM agent_run WHERE id IN (?, ?)", active, legacy); }
        assertThat(service.get().transport()).isEqualTo(ChatGptTransportService.Transport.SIWC_HTTP);
    }
    @Test void publicGatewaySendsChangedRolesInSamePersistedConversationAndParseFailureDoesNotAppendHistory() throws Exception {
        var auth = mock(ChatGptOAuthService.class);
        when(auth.authorization()).thenReturn(new PrivateOAuthStore.Credentials("oaiapp_wire", "subject-wire", null, null,
                "test-access", "test-refresh", Long.MAX_VALUE, "chatgpt.tokens.use.direct"));
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var captured = new java.util.ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        server.createContext("/responses", exchange -> {
            captured.add(json.readTree(exchange.getRequestBody()));
            var response = json.createObjectNode().put("id", "test-response").put("status", "completed");
            response.putArray("output").addObject().put("type", "message").put("role", "assistant")
                    .putArray("content").addObject().put("type", "output_text").put("text", "usable planning text");
            String frame = "data: " + json.createObjectNode().put("type", "response.completed").set("response", response) + "\n\n";
            byte[] bytes = frame.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length); try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var responses = new ChatGptResponsesClient(new ChatGptHttp(json, ""), auth, json, 2,
                    java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"));
            var settings = mock(com.novelagent.project.application.GlobalModelSettingsService.class);
            when(settings.get()).thenReturn(new com.novelagent.project.domain.GlobalModelSettings(
                    com.novelagent.planning.application.ModelProvider.LOCAL_CODEX, "test-model", "high", "deepseek-flash", 9));
            var direct = new com.novelagent.modelaccess.application.ChatGptDirectGateway(auth, store, responses, settings,
                    new com.novelagent.memory.application.ModelContextProperties());
            var transport = mock(ChatGptTransportService.class); when(transport.direct()).thenReturn(true);
            var codex = mock(com.novelagent.planning.infrastructure.CodexAppServerClient.class);
            var deepSeek = mock(com.novelagent.planning.infrastructure.DeepSeekStructuredOutputClient.class);
            var runs = mock(com.novelagent.agent.application.AgentRunRecorder.class);
            when(runs.progressSink()).thenReturn(ignored -> {});
            when(runs.record(any(), anyString(), any(), anyString(), anyString(), any(), any(), any())).thenAnswer(call -> {
                var result = ((java.util.function.Supplier<?>) call.getArgument(6)).get();
                ((java.util.function.Consumer<Object>) call.getArgument(7)).accept(result); return result;
            });
            var prompts = mock(com.novelagent.prompt.application.AgentPromptService.class);
            when(prompts.resolve(anyString(), anyString())).thenAnswer(call -> new com.novelagent.prompt.application.AgentPromptService.Resolved(
                    call.getArgument(0) + " phase", "wire-revision", call.getArgument(0) + " instructions"));
            var gateway = new com.novelagent.planning.infrastructure.StructuredModelGateway(codex,
                    mock(com.novelagent.planning.infrastructure.CodexAgentSessionRepository.class), deepSeek, runs,
                    new com.novelagent.memory.application.ModelContextProperties(), prompts, direct, transport);
            var schema = json.createObjectNode().put("type", "object"); schema.putObject("properties").putObject("text").put("type", "string");
            for (String phase : new String[]{"STORY_BIBLE", "OUTLINE"})
                assertThat(gateway.request(project, phase, com.novelagent.planning.application.ModelProvider.LOCAL_CODEX,
                        "default", "current data", schema, "text", 1000, com.novelagent.planning.infrastructure.CodexSessionPolicy.REUSE_THREAD))
                        .contains("usable planning text");
            assertThat(captured.get(0).path("instructions").asText()).startsWith("STORY_BIBLE instructions");
            assertThat(captured.get(1).path("instructions").asText()).startsWith("OUTLINE instructions");
            assertThat(captured.get(1).path("input").size()).isEqualTo(3);
            assertThat(captured.get(1).at("/input/0/content").asText()).contains("STORY_BIBLE phase");
            assertThat(captured.get(1).at("/input/2/content").asText()).contains("OUTLINE phase");
            verifyNoInteractions(codex, deepSeek);
            assertThatThrownBy(() -> gateway.request(project, "OUTLINE", com.novelagent.planning.application.ModelProvider.LOCAL_CODEX,
                    "default", "invalid data", schema, "text", 1000, com.novelagent.planning.infrastructure.CodexSessionPolicy.REUSE_THREAD,
                    output -> { throw new IllegalArgumentException("domain validation failed"); })).hasMessageContaining("domain validation");
            var history = store.claim(project, "STORY_PLANNING", ChatGptResponsesClient.accountBinding(auth.authorization()), "wire-revision", false);
            assertThat(history.history()).hasSize(4); store.release(history);
        } finally { server.stop(0); }
    }
    @AfterAll void cleanup() { if (jdbc != null && schema.matches("modelaccess_test_[a-f0-9]{32}")) jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }
    private String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
}
