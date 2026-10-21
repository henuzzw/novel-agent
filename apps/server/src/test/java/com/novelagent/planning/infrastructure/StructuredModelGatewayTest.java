package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.agent.application.AgentRunRecorder.RequestSnapshot;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.ingest.infrastructure.ImportAnalysisPrompt;
import com.novelagent.memory.application.MemoryBudgetAllocator;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.modelaccess.application.ChatGptDirectGateway;
import com.novelagent.modelaccess.application.ChatGptTransportService;
import com.novelagent.modelaccess.application.CodexProjectGateway;
import com.novelagent.modelaccess.infrastructure.ChatGptResponsesClient;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.application.CreativeBoundaryService;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.prompt.application.AgentPromptCatalog;
import com.novelagent.prompt.application.AgentPromptDefaults;
import com.novelagent.prompt.application.AgentPromptService;
import com.novelagent.prompt.infrastructure.AgentPromptRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StructuredModelGatewayTest {
    private JsonNode nativeSchema() throws Exception {
        return mapper.readTree("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"text\"],\"properties\":{\"text\":{\"type\":\"string\"}}}");
    }
    @Test void nativeReportsSendSchemaToLegacyAndDeepSeekAndRecordItWhileOtherCallsStayPlain() throws Exception {
        var contract = nativeSchema();
        when(codex.startThread(eq(project), anyString(), eq(settings))).thenReturn("json-thread");
        when(codex.runStructuredTurn(eq("json-thread"), eq(project), anyString(), eq(contract), eq(settings), any()))
                .thenReturn(new CodexAppServerClient.TurnResult("turn", "{\"text\":\"报告\"}"));
        when(deepSeek.request(eq("draft_loop_c"), anyString(), anyString(), eq(contract), eq(4000), eq(deepSettings)))
                .thenReturn(new DeepSeekStructuredOutputClient.ResponseResult("{\"text\":\"报告\"}", null));
        for (var provider : List.of(ModelProvider.LOCAL_CODEX, ModelProvider.DEEPSEEK)) {
            assertThat(gateway.requestJsonWithBoundaries(project, "DRAFT_JUDGE_REVISION", provider,
                    AgentPromptDefaults.system("DRAFT_JUDGE_REVISION"), "user", contract, "draft_loop_c", 4000,
                    CodexSessionPolicy.NEW_THREAD, null, "")).isEqualTo("{\"text\":\"报告\"}");
        }
        var snapshots = ArgumentCaptor.forClass(RequestSnapshot.class);
        var systems = ArgumentCaptor.forClass(String.class);
        var inputs = ArgumentCaptor.forClass(String.class);
        verify(runs, times(2)).record(eq(project), eq("DRAFT_JUDGE_REVISION"), any(), systems.capture(), inputs.capture(), snapshots.capture(), any(), any());
        assertThat(snapshots.getAllValues()).allSatisfy(value -> {
            assertThat(value.schemaName()).isEqualTo("draft_loop_c"); assertThat(value.schemaHash()).isNotBlank();
        });
        assertThat(systems.getAllValues()).allSatisfy(value -> assertThat(value).doesNotContain("不输出JSON", "不使用JSON"));
        assertThat(inputs.getAllValues()).allSatisfy(value -> assertThat(value).doesNotContain("【纯文本返回】", "## @"));
        verify(codex).runStructuredTurn(eq("json-thread"), eq(project), anyString(), eq(contract), eq(settings), any());
    }
    @Test void nativeInvalidJsonNeverSavesOrAcknowledgesSuccessfulMaterialDelivery() throws Exception {
        var contract = nativeSchema();
        when(codex.startThread(eq(project), anyString(), eq(settings))).thenReturn("json-thread");
        when(codex.runStructuredTurn(anyString(), eq(project), anyString(), eq(contract), eq(settings), any()))
                .thenReturn(new CodexAppServerClient.TurnResult("turn", "{\"text\":5}"));
        Consumer<String> save = mock(Consumer.class);
        assertThatThrownBy(() -> gateway.requestJsonWithBoundaries(project, "QUALITY_REVIEW", ModelProvider.LOCAL_CODEX,
                "system", "user", contract, "draft_loop_b", 4000, CodexSessionPolicy.NEW_THREAD, save, ""))
                .hasMessageContaining("$.text");
        verifyNoInteractions(save);
        verify(codex).closeMaterialScope("json-thread");
    }
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode schema = mapper.createObjectNode().put("type", "object")
            .set("properties", mapper.createObjectNode().set("text", mapper.createObjectNode().put("type", "string")));
    private final JsonNode absentSchema = NullNode.getInstance();
    private final String wireStage = "system" + new PlainTextOutputProtocol(schema).instructions();
    private final String wireSystem = AgentPromptDefaults.sessionSystemPrompt() + "\n\n" + AgentPromptService.PROTECTED_RULES;
    private final String legacySystem = wireSystem + LegacyCodexPromptReducer.READ_RULES;
    private final String wireUser = "【本轮服务端执行规范】\n" + wireStage + "\n【本轮创作资料】\nuser";
    private final UUID project = UUID.randomUUID();
    private final CodexAppServerClient codex = mock(CodexAppServerClient.class);
    private final CodexAgentSessionRepository sessions = mock(CodexAgentSessionRepository.class);
    private final DeepSeekStructuredOutputClient deepSeek = mock(DeepSeekStructuredOutputClient.class);
    private final AgentRunRecorder runs = mock(AgentRunRecorder.class);
    private final EffectiveSettings settings = new EffectiveSettings(ModelProvider.LOCAL_CODEX, "gpt-6-sol", "high", 4L);
    private final EffectiveSettings deepSettings = new EffectiveSettings(ModelProvider.DEEPSEEK, "deepseek-flash", "none", 4L);
    private final StructuredModelGateway gateway = gateway(new ModelContextProperties(), null);

    private StructuredModelGateway gateway(ModelContextProperties context, AgentPromptService prompts) {
        return new StructuredModelGateway(List.of(
                new LegacyCodexExecutor(runs, context, codex, new LegacyCodexPromptGateway(codex, sessions, mapper, context)),
                new DeepSeekExecutor(runs, context, deepSeek)), new ModelPromptAssembler(prompts, null, null), null);
    }
    @BeforeEach void setup() {
        when(runs.record(any(), anyString(), any(), anyString(), anyString(), any(RequestSnapshot.class), any(), any()))
                .thenAnswer(call -> {
                    var result = ((Supplier<?>) call.getArgument(6)).get();
                    ((Consumer<Object>) call.getArgument(7)).accept(result);
                    return result;
                });
        when(codex.effectiveSettings()).thenReturn(settings);
        when(deepSeek.effectiveSettings()).thenReturn(deepSettings);
        when(sessions.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }
    private String request(String workflow, ModelProvider provider, CodexSessionPolicy policy) {
        return gateway.request(project, workflow, provider, "system", "user", schema, "schema", 4000, policy);
    }
    private void codexOutput(String thread) {
        when(codex.runStructuredTurn(eq(thread), eq(project), anyString(), isNull(), eq(settings), any()))
                .thenReturn(new CodexAppServerClient.TurnResult("turn", "正文文本"));
    }
    @Test void oauthCodexSharesPlanningAndWritingButKeepsReviewIsolatedAndRecordsActualDelta() {
        var transport = mock(ChatGptTransportService.class);
        when(transport.get()).thenReturn(new ChatGptTransportService.Choice(
                ChatGptTransportService.Transport.SIWC_CODEX, 1));
        var direct = mock(ChatGptDirectGateway.class);
        when(direct.effectiveSettings()).thenReturn(settings);
        var projectGateway = mock(CodexProjectGateway.class);
        var prepared = mock(CodexProjectGateway.Prepared.class);
        when(projectGateway.prepare(eq(project), anyString(), anyBoolean(), anyString())).thenReturn(prepared);
        when(prepared.instructions(anyString())).thenAnswer(call -> call.getArgument(0) + "\nread rules");
        when(prepared.wireInput()).thenReturn("current instructions and material_id=exact-data-reference");
        when(prepared.run(anyString(), eq(settings), eq(4000), any(), any()))
                .thenReturn(new CodexAppServerClient.TurnResult("turn", "正文文本"));
        var selectedGateway = new StructuredModelGateway(List.of(
                new ProjectCodexExecutor(runs, new ModelContextProperties(), projectGateway, direct)),
                new ModelPromptAssembler(null, null, null), transport);
        for (String workflow : new String[]{"SNOWFLAKE_PLANNING", "CHARACTER_DESIGN", "OUTLINE", "MANUSCRIPT", "QUALITY_REVIEW"})
            assertThat(selectedGateway.request(project, workflow, ModelProvider.LOCAL_CODEX, "system", "user", schema,
                    "schema", 4000, CodexSessionPolicy.NEW_THREAD)).contains("正文文本");
        verify(projectGateway, times(4)).prepare(eq(project), eq("NOVEL_MAIN"), eq(false), anyString());
        verify(projectGateway).prepare(eq(project), eq("QUALITY_REVIEW"), eq(true), anyString());
        var snapshot = ArgumentCaptor.forClass(RequestSnapshot.class);
        verify(runs, times(5)).record(eq(project), anyString(), eq(ModelProvider.LOCAL_CODEX), anyString(),
                eq("current instructions and material_id=exact-data-reference"), snapshot.capture(), any(), any());
        assertThat(snapshot.getAllValues()).allSatisfy(value -> {
            assertThat(value.schemaHash()).isNull(); assertThat(value.schemaName()).isEqualTo("PLAIN_TEXT");
            assertThat(value.sessionPolicy()).startsWith("SIWC_CODEX_");
        });
        verify(prepared, times(5)).accept();
        verifyNoInteractions(codex, sessions, deepSeek);
    }
    @Test void standaloneNewThreadReplacesOldSessionAndNeverSendsOutputSchema() {
        var existing = CodexAgentSession.create(project, "QUALITY_REVIEW", "old");
        when(sessions.findByProjectIdAndWorkflowType(project, "QUALITY_REVIEW")).thenReturn(Optional.of(existing));
        when(codex.startThread(project, legacySystem, settings)).thenReturn("fresh");
        codexOutput("fresh");
        assertThat(request("QUALITY_REVIEW", ModelProvider.LOCAL_CODEX, CodexSessionPolicy.NEW_THREAD))
                .isEqualTo("{\"text\":\"正文文本\"}");
        assertThat(existing.getThreadId()).isEqualTo("fresh");
        verify(codex, never()).resumeThread(anyString(), any(), anyString(), any());
    }
    @Test void everyPlanningStepUsesSameConversationDespiteDifferentPromptsAndRequestedPolicies() {
        var existing = CodexAgentSession.create(project, PlanningConversationPolicy.KEY, "shared");
        existing.usePromptRevision(PlanningConversationPolicy.REVISION);
        when(sessions.findByProjectIdAndWorkflowType(project, PlanningConversationPolicy.KEY)).thenReturn(Optional.of(existing));
        codexOutput("shared");
        for (String workflow : new String[]{"BOOK_TITLE", "IMPORT_SOURCE_ANALYSIS", "SNOWFLAKE_PLANNING",
                "CHARACTER_DESIGN", "OUTLINE", "IMPORT_REVERSE_OUTLINE"}) {
            assertThat(request(workflow, ModelProvider.LOCAL_CODEX, CodexSessionPolicy.NEW_THREAD)).contains("正文文本");
        }
        verify(codex, times(6)).resumeThread("shared", project, legacySystem, settings);
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(codex, times(6)).runStructuredTurn(eq("shared"), eq(project), prompt.capture(), isNull(), eq(settings), any());
        assertThat(prompt.getAllValues()).hasSize(6);
        for (int i = 0; i < prompt.getAllValues().size(); i++) {
            String extra = i == 1 ? ImportAnalysisPrompt.WORLD_ASSESSMENT_RULES : "";
            assertThat(prompt.getAllValues().get(i)).isEqualTo("【本轮服务端执行规范】\nsystem" + extra
                    + new PlainTextOutputProtocol(schema).instructions() + "\n【本轮创作资料】\nuser");
        }
        verify(codex, never()).startThread(any(), anyString(), any());
    }
    @Test void missingSessionIsRebuiltButAuthenticationFailureIsNotRetried() {
        var existing = CodexAgentSession.create(project, "QUALITY_REVIEW", "missing");
        when(sessions.findByProjectIdAndWorkflowType(project, "QUALITY_REVIEW")).thenReturn(Optional.of(existing));
        doThrow(new CodexAppServerException("thread not found", -32000))
                .when(codex).resumeThread("missing", project, legacySystem, settings);
        when(codex.startThread(project, legacySystem, settings)).thenReturn("replacement");
        codexOutput("replacement");
        assertThat(request("QUALITY_REVIEW", ModelProvider.LOCAL_CODEX, CodexSessionPolicy.REUSE_THREAD)).contains("正文文本");
        doThrow(new CodexAppServerException("401 authentication: thread not found", -32000))
                .when(codex).resumeThread("replacement", project, legacySystem, settings);
        assertThatThrownBy(() -> request("QUALITY_REVIEW", ModelProvider.LOCAL_CODEX, CodexSessionPolicy.REUSE_THREAD))
                .isInstanceOf(CodexAppServerException.class);
        verify(codex, times(1)).startThread(project, legacySystem, settings);
    }

    @Test void pairedPlanningRolesReuseOneThreadUntilTheSharedConfigurationChanges() {
        var prompts = mock(AgentPromptService.class);
        var existing = CodexAgentSession.create(project, PlanningConversationPolicy.KEY, "shared");
        existing.usePromptRevision("planning:v1");
        when(sessions.findByProjectIdAndWorkflowType(project, PlanningConversationPolicy.KEY)).thenReturn(Optional.of(existing));
        when(prompts.resolve("CHARACTER_DESIGN", "system")).thenReturn(new AgentPromptService.Resolved("character phase", "planning:v1", "character system"));
        when(prompts.resolve("OUTLINE", "system"))
                .thenReturn(new AgentPromptService.Resolved("outline phase", "planning:v1", "outline system"))
                .thenReturn(new AgentPromptService.Resolved("edited phase", "planning:v2", "edited system"));
        codexOutput("shared");
        var paired = gateway(new ModelContextProperties(), prompts);
        for (String workflow : new String[]{"CHARACTER_DESIGN", "OUTLINE"}) {
            assertThat(paired.request(project, workflow, ModelProvider.LOCAL_CODEX, "system", "user", schema, "schema", 4000,
                    CodexSessionPolicy.NEW_THREAD)).contains("正文文本");
        }
        verify(codex).resumeThread("shared", project, "character system\n\n" + AgentPromptService.PROTECTED_RULES + LegacyCodexPromptReducer.READ_RULES, settings);
        verify(codex).resumeThread("shared", project, "outline system\n\n" + AgentPromptService.PROTECTED_RULES + LegacyCodexPromptReducer.READ_RULES, settings);
        verify(codex, never()).startThread(any(), anyString(), any());
        when(codex.startThread(project, "edited system\n\n" + AgentPromptService.PROTECTED_RULES + LegacyCodexPromptReducer.READ_RULES, settings)).thenReturn("fresh");
        codexOutput("fresh");
        assertThat(paired.request(project, "OUTLINE", ModelProvider.LOCAL_CODEX, "system", "user", schema, "schema", 4000,
                CodexSessionPolicy.REUSE_THREAD)).contains("正文文本");
        assertThat(existing.getThreadId()).isEqualTo("fresh");
        verify(codex).startThread(project, "edited system\n\n" + AgentPromptService.PROTECTED_RULES + LegacyCodexPromptReducer.READ_RULES, settings);
    }
    @Test void deepSeekRecordsRawTextAndNoSchemaOrCodexSession() {
        when(deepSeek.request("schema", wireSystem, wireUser, absentSchema, 4000, deepSettings))
                .thenReturn(new DeepSeekStructuredOutputClient.ResponseResult("纯文本结果", null));
        assertThat(request("OUTLINE", ModelProvider.DEEPSEEK, CodexSessionPolicy.NEW_THREAD)).contains("纯文本结果");
        var snapshot = ArgumentCaptor.forClass(RequestSnapshot.class);
        verify(runs).record(eq(project), eq("OUTLINE"), eq(ModelProvider.DEEPSEEK), eq(wireSystem), eq(wireUser),
                snapshot.capture(), any(), any());
        assertThat(snapshot.getValue().schemaName()).isEqualTo("PLAIN_TEXT");
        assertThat(snapshot.getValue().schemaHash()).isNull();
        assertThat(snapshot.getValue().sessionPolicy()).isEqualTo("STATELESS");
        verifyNoInteractions(sessions, codex);
    }

    @Test void currentProjectBoundariesReachWireAndFrozenCallsNeverReadMutableSettings() {
        var boundaries = mock(CreativeBoundaryService.class);
        when(boundaries.promptContext(project)).thenReturn("\n项目边界快照");
        var gateway = new StructuredModelGateway(List.of(new DeepSeekExecutor(runs, new ModelContextProperties(), deepSeek)),
                new ModelPromptAssembler(null, boundaries, null), null);
        when(deepSeek.request(anyString(), anyString(), anyString(), eq(absentSchema), anyInt(), any()))
                .thenReturn(new DeepSeekStructuredOutputClient.ResponseResult("文本", null));
        gateway.request(project, "OUTLINE", ModelProvider.DEEPSEEK, "system", "user", schema, "schema", 4000, CodexSessionPolicy.NEW_THREAD);
        verify(deepSeek).request("schema", wireSystem,
                "【本轮服务端执行规范】\n" + wireStage + "\n项目边界快照\n【本轮创作资料】\nuser",
                absentSchema, 4000, deepSettings);
        clearInvocations(boundaries);
        gateway.requestWithBoundaries(project, "QUALITY_REVIEW", ModelProvider.DEEPSEEK,
                "system", "user", schema, "schema", 4000, CodexSessionPolicy.NEW_THREAD, null, "\n冻结边界");
        verifyNoInteractions(boundaries);
        verify(deepSeek).request("schema", wireSystem,
                "【本轮服务端执行规范】\n" + wireStage + "\n冻结边界\n【本轮创作资料】\nuser",
                absentSchema, 4000, deepSettings);
    }

    @Test void snowflakeDefaultPairReachesProviderAndRecorderAsPlainText() {
        var prompts = new AgentPromptService(new AgentPromptCatalog(),
                mock(AgentPromptRepository.class),
                new CurrentActorProvider(UUID.randomUUID()));
        String stage = AgentPromptDefaults.system("SNOWFLAKE_PLANNING");
        String role = AgentPromptDefaults.sessionSystemPrompt("SNOWFLAKE_PLANNING");
        String system = role + "\n\n" + AgentPromptService.protectedRules("SNOWFLAKE_PLANNING");
        String user = "【本轮服务端执行规范】\n" + stage + new PlainTextOutputProtocol(schema).instructions()
                + "\n【本轮创作资料】\n冻结的本步创作资料";
        when(deepSeek.request("snowflake_core", system, user, absentSchema, 1000, deepSettings))
                .thenReturn(new DeepSeekStructuredOutputClient.ResponseResult("本步自由文本", null));
        assertThat(gateway(new ModelContextProperties(), prompts).request(project, "SNOWFLAKE_PLANNING",
                ModelProvider.DEEPSEEK, stage, "冻结的本步创作资料", schema, "snowflake_core", 1000,
                CodexSessionPolicy.REUSE_THREAD)).isEqualTo("{\"text\":\"本步自由文本\"}");
        verify(deepSeek).request("snowflake_core", system, user, absentSchema, 1000, deepSettings);
        var snapshot = ArgumentCaptor.forClass(RequestSnapshot.class);
        verify(runs).record(eq(project), eq("SNOWFLAKE_PLANNING"), eq(ModelProvider.DEEPSEEK), eq(system), eq(user),
                snapshot.capture(), any(), any());
        assertThat(snapshot.getValue().schemaName()).isEqualTo("PLAIN_TEXT");
        assertThat(snapshot.getValue().schemaHash()).isNull();
        verifyNoInteractions(sessions, codex);
    }
    @Test void parsingFailureHappensInsideRecordedAction() {
        when(deepSeek.request(anyString(), anyString(), anyString(), eq(absentSchema), anyInt(), any()))
                .thenReturn(new DeepSeekStructuredOutputClient.ResponseResult("raw", null));
        var error = new IllegalArgumentException("解析失败");
        assertThatThrownBy(() -> gateway.request(project, "QUALITY_REVIEW", ModelProvider.DEEPSEEK, "system", "user",
                schema, "schema", 4000, CodexSessionPolicy.NEW_THREAD, raw -> {
                    assertThat(raw).isEqualTo("{\"text\":\"raw\"}"); throw error;
                })).isSameAs(error);
    }
    @Test void freezesPromptAndSettingsBeforeDispatchWithoutMutatingSavedTemplate() {
        var prompts = mock(AgentPromptService.class);
        when(prompts.resolve("MANUSCRIPT", "system"))
                .thenReturn(new AgentPromptService.Resolved("edited\n【本阶段返回协议：保留现有结构化接口】\n只输出JSON", "MANUSCRIPT:v3", "custom system"));
        String effectiveSystem = "custom system\n\n" + AgentPromptService.PROTECTED_RULES;
        String effectiveUser = "【本轮服务端执行规范】\nedited" + new PlainTextOutputProtocol(schema).instructions() + "\n【本轮创作资料】\nuser";
        when(deepSeek.request("schema", effectiveSystem, effectiveUser, absentSchema, 4000, deepSettings))
                .thenReturn(new DeepSeekStructuredOutputClient.ResponseResult("结果", null));
        assertThat(gateway(new ModelContextProperties(), prompts).request(project, "MANUSCRIPT", ModelProvider.DEEPSEEK,
                "system", "user", schema, "schema", 4000, CodexSessionPolicy.NEW_THREAD)).contains("结果");
        verify(prompts, times(1)).resolve("MANUSCRIPT", "system");
        verify(deepSeek, times(1)).effectiveSettings();
        verify(runs).record(eq(project), eq("MANUSCRIPT"), eq(ModelProvider.DEEPSEEK), eq(effectiveSystem),
                eq(effectiveUser), any(RequestSnapshot.class), any(), any());
        when(codex.startThread(project, effectiveSystem + LegacyCodexPromptReducer.READ_RULES, settings)).thenReturn("custom-thread");
        codexOutput("custom-thread");
        assertThat(gateway(new ModelContextProperties(), prompts).request(project, "MANUSCRIPT", ModelProvider.LOCAL_CODEX,
                "system", "user", schema, "schema", 4000, CodexSessionPolicy.NEW_THREAD)).contains("正文文本");
        verify(codex).runStructuredTurn(eq("custom-thread"), eq(project), eq(effectiveUser), isNull(), eq(settings), any());
    }
    @Test void outputAndSafetyReservationsCountAtExactCapacityBoundary() {
        var context = new ModelContextProperties();
        int input = MemoryBudgetAllocator.estimateTokens(wireSystem + "\n\n" + wireUser + "\n\n" + absentSchema);
        var capacity = new ModelContextProperties.Capacity(input + 19, 10);
        context.getModels().put(ModelProvider.DEEPSEEK, capacity);
        var bounded = gateway(context, null);
        assertThatThrownBy(() -> bounded.request(project, "OUTLINE", ModelProvider.DEEPSEEK, "system", "user",
                schema, "schema", 10, CodexSessionPolicy.NEW_THREAD)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(runs, sessions);
        capacity.setContextWindowTokens(input + 20);
        when(deepSeek.request("schema", wireSystem, wireUser, absentSchema, 10, deepSettings))
                .thenReturn(new DeepSeekStructuredOutputClient.ResponseResult("完整结果", null));
        assertThat(bounded.request(project, "OUTLINE", ModelProvider.DEEPSEEK, "system", "user", schema, "schema", 10,
                CodexSessionPolicy.NEW_THREAD)).contains("完整结果");
    }

    @Test void legacyReferencesOnlySuccessfullySavedIdenticalDataAndRecordsActualWire() {
        when(codex.supportsMaterialTool("shared")).thenReturn(true);
        var session = CodexAgentSession.create(project, PlanningConversationPolicy.KEY, "shared");
        session.usePromptRevision(PlanningConversationPolicy.REVISION);
        when(sessions.findByProjectIdAndWorkflowType(project, PlanningConversationPolicy.KEY)).thenReturn(Optional.of(session));
        codexOutput("shared");
        String data = "{\"text\":\"" + "同一份资料".repeat(300) + "\"}";
        for (int i = 0; i < 2; i++) gateway.request(project, "OUTLINE", ModelProvider.LOCAL_CODEX,
                "system", data, schema, "schema", 4000, CodexSessionPolicy.REUSE_THREAD);
        var wire = ArgumentCaptor.forClass(String.class);
        verify(codex, times(2)).runStructuredTurn(eq("shared"), eq(project), wire.capture(), isNull(), eq(settings), any());
        assertThat(wire.getAllValues().getFirst()).contains(data);
        assertThat(wire.getAllValues().getLast()).doesNotContain(data).contains("material_id=", "【本轮服务端执行规范】");
        var snapshot = ArgumentCaptor.forClass(RequestSnapshot.class);
        var recorded = ArgumentCaptor.forClass(String.class);
        verify(runs, times(2)).record(eq(project), eq("OUTLINE"), eq(ModelProvider.LOCAL_CODEX), eq(legacySystem),
                recorded.capture(), snapshot.capture(), any(), any());
        assertThat(recorded.getAllValues()).containsExactlyElementsOf(wire.getAllValues());
        assertThat(snapshot.getAllValues()).allSatisfy(value -> {
            assertThat(value.sessionPolicy()).isEqualTo("APP_SERVER_DELTA_REUSE_THREAD");
            assertThat(value.schemaHash()).isNull();
        });
    }

    @Test void legacySaveFailureDoesNotAcknowledgeDataAndReleasesProjectLock() {
        var session = CodexAgentSession.create(project, PlanningConversationPolicy.KEY, "shared");
        session.usePromptRevision(PlanningConversationPolicy.REVISION);
        when(sessions.findByProjectIdAndWorkflowType(project, PlanningConversationPolicy.KEY)).thenReturn(Optional.of(session));
        codexOutput("shared");
        String data = "{\"text\":\"" + "retry exact data ".repeat(100) + "\"}";
        assertThatThrownBy(() -> gateway.request(project, "OUTLINE", ModelProvider.LOCAL_CODEX, "system", data,
                schema, "schema", 4000, CodexSessionPolicy.REUSE_THREAD,
                output -> { throw new IllegalArgumentException("save failed"); })).hasMessage("save failed");
        verify(sessions, never()).saveAndFlush(any());
        gateway.request(project, "OUTLINE", ModelProvider.LOCAL_CODEX, "system", data, schema, "schema", 4000, CodexSessionPolicy.REUSE_THREAD);
        var wire = ArgumentCaptor.forClass(String.class);
        verify(codex, times(2)).runStructuredTurn(eq("shared"), eq(project), wire.capture(), isNull(), eq(settings), any());
        assertThat(wire.getAllValues()).allSatisfy(value -> assertThat(value).contains(data).doesNotContain("material_id="));
    }

    @Test void explicitHttpEntryRecordsCompleteHistoryAndOnlyAcceptsAfterBusinessSave() {
        var transport = mock(ChatGptTransportService.class);
        when(transport.get()).thenReturn(new ChatGptTransportService.Choice(
                ChatGptTransportService.Transport.SIWC_HTTP, 1));
        var direct = mock(ChatGptDirectGateway.class);
        var prepared = mock(ChatGptDirectGateway.Prepared.class);
        when(direct.effectiveSettings()).thenReturn(settings);
        when(direct.prepare(eq(project), eq(PlanningConversationPolicy.KEY), eq(wireSystem), eq(wireUser),
                eq(CodexSessionPolicy.REUSE_THREAD), eq(PlanningConversationPolicy.REVISION), eq(4000), isNull())).thenReturn(prepared);
        when(prepared.wireInput()).thenReturn("[full history and current input]");
        when(prepared.budget()).thenReturn(new AgentRunRecorder.ContextBudget(128000, 4000, 20, 4000));
        var result = new ChatGptResponsesClient.Result("response", "text", null, mapper.createArrayNode());
        when(prepared.run(eq(settings), any(), any(), isNull())).thenReturn(result);
        var selected = new StructuredModelGateway(List.of(new ChatGptHttpExecutor(runs, new ModelContextProperties(), direct)),
                new ModelPromptAssembler(null, null, null), transport);
        selected.request(project, "OUTLINE", ModelProvider.LOCAL_CODEX, "system", "user", schema, "schema", 4000,
                CodexSessionPolicy.REUSE_THREAD, output -> verify(prepared, never()).accept(any()));
        verify(prepared).accept(result);
        verify(prepared).close();
        verify(runs).record(eq(project), eq("OUTLINE"), eq(ModelProvider.LOCAL_CODEX), eq(wireSystem),
                eq("[full history and current input]"), any(RequestSnapshot.class), any(), any());
        verify(transport, times(1)).get();
        verifyNoInteractions(codex, sessions, deepSeek);
    }

    @Test void missingSelectedEntryNeverFallsBackToAnotherProvider() {
        var transport = mock(ChatGptTransportService.class);
        when(transport.get()).thenReturn(new ChatGptTransportService.Choice(
                ChatGptTransportService.Transport.SIWC_CODEX, 1));
        var selected = new StructuredModelGateway(List.of(new DeepSeekExecutor(runs, new ModelContextProperties(), deepSeek)),
                new ModelPromptAssembler(null, null, null), transport);
        assertThatThrownBy(() -> selected.request(project, "OUTLINE", ModelProvider.LOCAL_CODEX, "system", "user", schema,
                "schema", 4000, CodexSessionPolicy.REUSE_THREAD)).hasMessageContaining("未初始化");
        verifyNoInteractions(codex, sessions, deepSeek, runs);
    }
}
