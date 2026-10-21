package com.novelagent.modelaccess.infrastructure;

import static com.novelagent.support.TestDatabase.env;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.modelaccess.application.ChatGptDirectGateway;
import com.novelagent.modelaccess.application.ChatGptOAuthService;
import com.novelagent.modelaccess.application.ChatGptTransportService;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.ChatGptHttpExecutor;
import com.novelagent.planning.infrastructure.CodexAppServerClient;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.DeepSeekStructuredOutputClient;
import com.novelagent.planning.infrastructure.ModelPromptAssembler;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.GlobalModelSettingsService;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.GlobalModelSettings;
import com.novelagent.prompt.application.AgentPromptService;
import com.novelagent.support.DatabaseTest;
import com.novelagent.support.TestDatabase;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

@DatabaseTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpConversationDatabaseTest {
    private final String schema = "modelaccess_test_" + UUID.randomUUID().toString().replace("-", "");
    private final UUID owner = UUID.randomUUID(), project = UUID.randomUUID();
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private HttpConversationStore store;
    private DataSourceTransactionManager manager;
    @BeforeAll void setup() throws Exception {
        var source = new DriverManagerDataSource(TestDatabase.url() + "?currentSchema=" + schema + "&sslmode=" + env("DB_SSL_MODE", "disable"),
                env("DB_USERNAME", "novel_app"), env("DB_PASSWORD", "change-me"));
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("CREATE TABLE novel_project(id uuid PRIMARY KEY, owner_id uuid NOT NULL)");
        jdbc.execute("CREATE TABLE agent_run(id uuid PRIMARY KEY, project_id uuid, status varchar(32), started_at timestamptz DEFAULT now())");
        try (var stream = getClass().getResourceAsStream("/db/migration/V056__chatgpt_direct_transport.sql")) {
            jdbc.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (var stream = getClass().getResourceAsStream("/db/migration/V057__codex_project_sessions.sql")) {
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
    @Test void codexLeasesOrderAllProjectStagesAndKeepExactVersionedMaterials() {
        var codex = new CodexConversationStore(jdbc, json, new CurrentActorProvider(owner), manager);
        var first = codex.claim(project, "NOVEL_MAIN", "codex-account", false);
        assertThatThrownBy(() -> codex.claim(project, "QUALITY_REVIEW", "codex-account", true)).hasMessageContaining("正在生成");
        String content = "完整资料，不是摘要"; String id = CodexMaterialDelta.fingerprint(content);
        codex.saveMaterials(first, Map.of(id, content)); codex.bind(first, "persisted-thread");
        assertThat(codex.readMaterial(first, id)).isEqualTo(content);
        codex.complete(first, Set.of(id), 120000);
        var next = codex.claim(project, "NOVEL_MAIN", "codex-account", false);
        assertThat(next.thread()).isEqualTo("persisted-thread"); assertThat(next.sent()).containsExactly(id);
        assertThat(next.contextTokens()).isEqualTo(120000);
        assertThatThrownBy(() -> codex.readMaterial(first, id)).hasMessageContaining("租约");
        codex.abort(next);
        var retry = codex.claim(project, "NOVEL_MAIN", "codex-account", false);
        assertThat(retry.thread()).isNull(); assertThat(retry.sent()).isEmpty();
        assertThat(codex.readMaterial(retry, id)).isEqualTo(content);
        codex.bind(retry, "retry-thread"); codex.complete(retry, Set.of(id), 100);
        var changed = codex.claim(project, "NOVEL_MAIN", "different-account", false);
        assertThat(changed.thread()).isNull(); assertThat(changed.sent()).isEmpty(); codex.release(changed);
        var intruder = new CodexConversationStore(jdbc, json, new CurrentActorProvider(UUID.randomUUID()), manager);
        assertThatThrownBy(() -> intruder.claim(project, "NOVEL_MAIN", "codex-account", false)).hasMessageContaining("不属于");
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
                .isInstanceOf(ResourceVersionConflictException.class);
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
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var captured = new ArrayList<JsonNode>();
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
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"));
            var settings = mock(GlobalModelSettingsService.class);
            when(settings.get()).thenReturn(new GlobalModelSettings(
                    ModelProvider.LOCAL_CODEX, "test-model", "high", "deepseek-flash", 9));
            var direct = new ChatGptDirectGateway(auth, store, responses, settings,
                    new ModelContextProperties());
            var transport = mock(ChatGptTransportService.class);
            when(transport.get()).thenReturn(new ChatGptTransportService.Choice(ChatGptTransportService.Transport.SIWC_HTTP, 1));
            var codex = mock(CodexAppServerClient.class);
            var deepSeek = mock(DeepSeekStructuredOutputClient.class);
            var runs = mock(AgentRunRecorder.class);
            when(runs.progressSink()).thenReturn(ignored -> {});
            when(runs.record(any(), anyString(), any(), anyString(), anyString(), any(), any(), any())).thenAnswer(call -> {
                var result = ((Supplier<?>) call.getArgument(6)).get();
                ((Consumer<Object>) call.getArgument(7)).accept(result); return result;
            });
            var prompts = mock(AgentPromptService.class);
            when(prompts.resolve(anyString(), anyString())).thenAnswer(call -> new AgentPromptService.Resolved(
                    call.getArgument(0) + " phase", "wire-revision", call.getArgument(0) + " instructions"));
            var gateway = new StructuredModelGateway(List.of(
                    new ChatGptHttpExecutor(runs,
                            new ModelContextProperties(), direct)),
                    new ModelPromptAssembler(prompts, null, null), transport);
            var schema = json.createObjectNode().put("type", "object"); schema.putObject("properties").putObject("text").put("type", "string");
            for (String phase : new String[]{"STORY_BIBLE", "OUTLINE"})
                assertThat(gateway.request(project, phase, ModelProvider.LOCAL_CODEX,
                        "default", "current data", schema, "text", 1000, CodexSessionPolicy.REUSE_THREAD))
                        .contains("usable planning text");
            assertThat(captured.get(0).path("instructions").asText()).startsWith("STORY_BIBLE instructions");
            assertThat(captured.get(1).path("instructions").asText()).startsWith("OUTLINE instructions");
            assertThat(captured.get(1).path("input").size()).isEqualTo(3);
            assertThat(captured.get(1).at("/input/0/content").asText()).contains("STORY_BIBLE phase");
            assertThat(captured.get(1).at("/input/2/content").asText()).contains("OUTLINE phase");
            verifyNoInteractions(codex, deepSeek);
            assertThatThrownBy(() -> gateway.request(project, "OUTLINE", ModelProvider.LOCAL_CODEX,
                    "default", "invalid data", schema, "text", 1000, CodexSessionPolicy.REUSE_THREAD,
                    output -> { throw new IllegalArgumentException("domain validation failed"); })).hasMessageContaining("domain validation");
            var history = store.claim(project, "STORY_PLANNING", ChatGptResponsesClient.accountBinding(auth.authorization()), "wire-revision", false);
            assertThat(history.history()).hasSize(4); store.release(history);
        } finally { server.stop(0); }
    }
    @AfterAll void cleanup() { if (jdbc != null && schema.matches("modelaccess_test_[a-f0-9]{32}")) jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }
}
