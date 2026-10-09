package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.agent.application.AgentRunRecorder.RequestSnapshot;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.prompt.application.AgentPromptService;
import com.novelagent.prompt.application.AgentPromptDefaults;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StructuredModelGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonNode schema = mapper.createObjectNode().put("type", "object")
            .set("properties", mapper.createObjectNode().set("text", mapper.createObjectNode().put("type", "string")));
    private final JsonNode absentSchema = NullNode.getInstance();
    private final String wireStage = "system" + new PlainTextOutputProtocol(schema).instructions();
    private final String wireSystem = AgentPromptDefaults.sessionSystemPrompt() + "\n\n" + AgentPromptService.PROTECTED_RULES;
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
        return new StructuredModelGateway(codex, sessions, deepSeek, runs, context, prompts);
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
        when(codex.runStructuredTurn(eq(thread), eq(project), anyString(), eq(absentSchema), eq(settings), any()))
                .thenReturn(new CodexAppServerClient.TurnResult("turn", "正文文本"));
    }
    @Test void standaloneNewThreadReplacesOldSessionAndNeverSendsOutputSchema() {
        var existing = CodexAgentSession.create(project, "QUALITY_REVIEW", "old");
        when(sessions.findByProjectIdAndWorkflowType(project, "QUALITY_REVIEW")).thenReturn(Optional.of(existing));
        when(codex.startThread(project, wireSystem, settings)).thenReturn("fresh");
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
                "CHARACTER_DESIGN", "STORY_DIRECTION", "STORY_BIBLE", "OUTLINE", "IMPORT_REVERSE_BIBLE", "IMPORT_REVERSE_OUTLINE"}) {
            assertThat(request(workflow, ModelProvider.LOCAL_CODEX, CodexSessionPolicy.NEW_THREAD)).contains("正文文本");
        }
        verify(codex, times(9)).resumeThread("shared", project, wireSystem, settings);
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(codex, times(9)).runStructuredTurn(eq("shared"), eq(project), prompt.capture(), eq(absentSchema), eq(settings), any());
        assertThat(prompt.getAllValues()).containsOnly(wireUser);
        verify(codex, never()).startThread(any(), anyString(), any());
    }
    @Test void missingSessionIsRebuiltButAuthenticationFailureIsNotRetried() {
        var existing = CodexAgentSession.create(project, "QUALITY_REVIEW", "missing");
        when(sessions.findByProjectIdAndWorkflowType(project, "QUALITY_REVIEW")).thenReturn(Optional.of(existing));
        doThrow(new CodexAppServerException("thread not found", -32000))
                .when(codex).resumeThread("missing", project, wireSystem, settings);
        when(codex.startThread(project, wireSystem, settings)).thenReturn("replacement");
        codexOutput("replacement");
        assertThat(request("QUALITY_REVIEW", ModelProvider.LOCAL_CODEX, CodexSessionPolicy.REUSE_THREAD)).contains("正文文本");
        doThrow(new CodexAppServerException("401 authentication: thread not found", -32000))
                .when(codex).resumeThread("replacement", project, wireSystem, settings);
        assertThatThrownBy(() -> request("QUALITY_REVIEW", ModelProvider.LOCAL_CODEX, CodexSessionPolicy.REUSE_THREAD))
                .isInstanceOf(CodexAppServerException.class);
        verify(codex, times(1)).startThread(project, wireSystem, settings);
    }

    @Test void pairedPlanningRolesReuseOneThreadUntilTheSharedConfigurationChanges() {
        var prompts = mock(AgentPromptService.class);
        var existing = CodexAgentSession.create(project, PlanningConversationPolicy.KEY, "shared");
        existing.usePromptRevision("planning:v1");
        when(sessions.findByProjectIdAndWorkflowType(project, PlanningConversationPolicy.KEY)).thenReturn(Optional.of(existing));
        when(prompts.resolve("STORY_BIBLE", "system")).thenReturn(new AgentPromptService.Resolved("bible phase", "planning:v1", "bible system"));
        when(prompts.resolve("OUTLINE", "system"))
                .thenReturn(new AgentPromptService.Resolved("outline phase", "planning:v1", "outline system"))
                .thenReturn(new AgentPromptService.Resolved("edited phase", "planning:v2", "edited system"));
        codexOutput("shared");
        var paired = gateway(new ModelContextProperties(), prompts);
        for (String workflow : new String[]{"STORY_BIBLE", "OUTLINE"}) {
            assertThat(paired.request(project, workflow, ModelProvider.LOCAL_CODEX, "system", "user", schema, "schema", 4000,
                    CodexSessionPolicy.NEW_THREAD)).contains("正文文本");
        }
        verify(codex).resumeThread("shared", project, "bible system\n\n" + AgentPromptService.PROTECTED_RULES, settings);
        verify(codex).resumeThread("shared", project, "outline system\n\n" + AgentPromptService.PROTECTED_RULES, settings);
        verify(codex, never()).startThread(any(), anyString(), any());
        when(codex.startThread(project, "edited system\n\n" + AgentPromptService.PROTECTED_RULES, settings)).thenReturn("fresh");
        codexOutput("fresh");
        assertThat(paired.request(project, "OUTLINE", ModelProvider.LOCAL_CODEX, "system", "user", schema, "schema", 4000,
                CodexSessionPolicy.REUSE_THREAD)).contains("正文文本");
        assertThat(existing.getThreadId()).isEqualTo("fresh");
        verify(codex).startThread(project, "edited system\n\n" + AgentPromptService.PROTECTED_RULES, settings);
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
        when(codex.startThread(project, effectiveSystem, settings)).thenReturn("custom-thread");
        codexOutput("custom-thread");
        assertThat(gateway(new ModelContextProperties(), prompts).request(project, "MANUSCRIPT", ModelProvider.LOCAL_CODEX,
                "system", "user", schema, "schema", 4000, CodexSessionPolicy.NEW_THREAD)).contains("正文文本");
        verify(codex).runStructuredTurn(eq("custom-thread"), eq(project), eq(effectiveUser), eq(absentSchema), eq(settings), any());
    }
    @Test void outputAndSafetyReservationsCountAtExactCapacityBoundary() {
        var context = new ModelContextProperties();
        int input = com.novelagent.memory.application.MemoryBudgetAllocator.estimateTokens(wireSystem + "\n\n" + wireUser + "\n\n" + absentSchema);
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
}
