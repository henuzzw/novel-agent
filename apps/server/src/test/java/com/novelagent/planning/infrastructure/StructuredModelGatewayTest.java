package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.agent.application.AgentRunRecorder.RequestSnapshot;
import com.novelagent.agent.application.AgentRunRecorder.Usage;
import com.novelagent.planning.application.ModelProvider;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StructuredModelGatewayTest {
    private final CodexAppServerClient codex = mock(CodexAppServerClient.class);
    private final CodexAgentSessionRepository sessions = mock(CodexAgentSessionRepository.class);
    private final DeepSeekStructuredOutputClient deepSeek = mock(DeepSeekStructuredOutputClient.class);
    private final AgentRunRecorder runs = mock(AgentRunRecorder.class);
    private final StructuredModelGateway gateway = new StructuredModelGateway(codex, sessions, deepSeek, runs);
    private final UUID projectId = UUID.randomUUID();
    private final JsonNode schema = new ObjectMapper().createObjectNode();
    private final EffectiveSettings codexSettings = new EffectiveSettings(ModelProvider.LOCAL_CODEX, "gpt-6-sol", "high", 4L);
    private final EffectiveSettings deepSeekSettings = new EffectiveSettings(ModelProvider.DEEPSEEK, "deepseek-flash", "none", 4L);

    @BeforeEach
    void recordInvokesModelAction() {
        when(runs.record(any(), anyString(), any(), anyString(), anyString(), any(RequestSnapshot.class), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(6)).get());
        when(codex.effectiveSettings()).thenReturn(codexSettings);
        when(deepSeek.effectiveSettings()).thenReturn(deepSeekSettings);
        when(sessions.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void newThreadPolicyReplacesAnExistingSessionWithoutResumingIt() {
        CodexAgentSession existing = CodexAgentSession.create(projectId, "OUTLINE", "older-thread");
        when(sessions.findByProjectIdAndWorkflowType(projectId, "OUTLINE"))
                .thenReturn(Optional.of(existing));
        when(codex.startThread(projectId, "system", codexSettings)).thenReturn("fresh-thread");
        when(codex.runStructuredTurn(eq("fresh-thread"), eq(projectId), eq("user"), eq(schema), eq(codexSettings), any()))
                .thenReturn(new CodexAppServerClient.TurnResult("turn-1", "output"));

        String result = request("OUTLINE", ModelProvider.LOCAL_CODEX, CodexSessionPolicy.NEW_THREAD);

        assertThat(result).isEqualTo("output");
        assertThat(existing.getThreadId()).isEqualTo("fresh-thread");
        verify(codex, never()).resumeThread(anyString(), any(), anyString(), any());
        verify(sessions, times(2)).saveAndFlush(existing);
    }

    @Test
    void reusePolicyResumesAnExistingSession() {
        CodexAgentSession existing = CodexAgentSession.create(projectId, "REVIEW", "existing-thread");
        when(sessions.findByProjectIdAndWorkflowType(projectId, "REVIEW"))
                .thenReturn(Optional.of(existing));
        when(codex.runStructuredTurn(eq("existing-thread"), eq(projectId), eq("user"), eq(schema), eq(codexSettings), any()))
                .thenReturn(new CodexAppServerClient.TurnResult("turn-2", "continued"));

        String result = request("REVIEW", ModelProvider.LOCAL_CODEX, CodexSessionPolicy.REUSE_THREAD);

        assertThat(result).isEqualTo("continued");
        verify(codex).resumeThread("existing-thread", projectId, "system", codexSettings);
        verify(codex, never()).startThread(any(), anyString(), any());
        verify(sessions).saveAndFlush(existing);
    }

    @Test
    void reusePolicyRebuildsAMissingCodexThread() {
        CodexAgentSession existing = CodexAgentSession.create(projectId, "REVIEW", "missing-thread");
        when(sessions.findByProjectIdAndWorkflowType(projectId, "REVIEW"))
                .thenReturn(Optional.of(existing));
        org.mockito.Mockito.doThrow(new CodexAppServerException("thread not found", -32000))
                .when(codex).resumeThread("missing-thread", projectId, "system", codexSettings);
        when(codex.startThread(projectId, "system", codexSettings)).thenReturn("replacement-thread");
        when(codex.runStructuredTurn(eq("replacement-thread"), eq(projectId), eq("user"), eq(schema), eq(codexSettings), any()))
                .thenReturn(new CodexAppServerClient.TurnResult("turn-3", "recovered"));

        String result = request("REVIEW", ModelProvider.LOCAL_CODEX, CodexSessionPolicy.REUSE_THREAD);

        assertThat(result).isEqualTo("recovered");
        assertThat(existing.getThreadId()).isEqualTo("replacement-thread");
        verify(sessions, times(2)).saveAndFlush(existing);
    }

    @Test
    void deepSeekRequestsAreRecordedWithoutTouchingCodexSessions() {
        when(deepSeek.effectiveSettings()).thenReturn(
                new EffectiveSettings(ModelProvider.LOCAL_CODEX, "deepseek-flash", "none", 4L));
        when(deepSeek.request("schema-name", "system", "user", schema, 4000, deepSeekSettings))
                .thenReturn(new DeepSeekStructuredOutputClient.ResponseResult("deepseek-output", new Usage(50, 10, 60L, null, null)));

        String result = request("STORY_DIRECTION", ModelProvider.DEEPSEEK, CodexSessionPolicy.REUSE_THREAD);

        assertThat(result).isEqualTo("deepseek-output");
        var snapshot = org.mockito.ArgumentCaptor.forClass(RequestSnapshot.class);
        verify(runs).record(eq(projectId), eq("STORY_DIRECTION"), eq(ModelProvider.DEEPSEEK),
                eq("system"), eq("user"), snapshot.capture(), any());
        assertThat(snapshot.getValue().effectiveSettings().provider()).isEqualTo(ModelProvider.DEEPSEEK);
        verify(deepSeek, times(1)).effectiveSettings();
        verify(sessions, never()).findByProjectIdAndWorkflowType(any(), anyString());
        verify(codex, never()).startThread(any(), anyString(), any());
    }

    @Test
    void settingsAreCapturedOnceAndSurviveAChangeDuringThreadStart() {
        EffectiveSettings changed = new EffectiveSettings(ModelProvider.LOCAL_CODEX, "gpt-6-luna", "low", 5L);
        when(codex.effectiveSettings()).thenReturn(codexSettings, changed);
        when(codex.startThread(projectId, "system", codexSettings)).thenReturn("frozen-thread");
        when(codex.runStructuredTurn(eq("frozen-thread"), eq(projectId), eq("user"), eq(schema), eq(codexSettings), any()))
                .thenReturn(new CodexAppServerClient.TurnResult("turn", "output"));

        assertThat(request("OUTLINE", ModelProvider.LOCAL_CODEX, CodexSessionPolicy.NEW_THREAD)).isEqualTo("output");
        verify(codex, times(1)).effectiveSettings();
        var snapshot = org.mockito.ArgumentCaptor.forClass(RequestSnapshot.class);
        verify(runs).record(eq(projectId), eq("OUTLINE"), eq(ModelProvider.LOCAL_CODEX),
                eq("system"), eq("user"), snapshot.capture(), any());
        assertThat(snapshot.getValue().effectiveSettings()).isEqualTo(codexSettings);
        assertThat(snapshot.getValue().requestedMaxOutputTokens()).isEqualTo(4000);
        assertThat(snapshot.getValue().maxOutputTokens()).isNull();
        assertThat(snapshot.getValue().sessionPolicy()).isEqualTo("NEW_THREAD");
        assertThat(snapshot.getValue().schemaHash()).hasSize(64);
    }

    @Test
    void authenticationFailureDoesNotRebuildOrRetryEvenIfErrorMentionsMissingThread() {
        var existing = CodexAgentSession.create(projectId, "REVIEW", "existing-thread");
        when(sessions.findByProjectIdAndWorkflowType(projectId, "REVIEW")).thenReturn(Optional.of(existing));
        org.mockito.Mockito.doThrow(new CodexAppServerException("401 authentication: thread not found", -32000))
                .when(codex).resumeThread("existing-thread", projectId, "system", codexSettings);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> request("REVIEW", ModelProvider.LOCAL_CODEX,
                CodexSessionPolicy.REUSE_THREAD)).isInstanceOf(CodexAppServerException.class);
        verify(codex, never()).startThread(any(), anyString(), any());
        verify(codex, never()).runStructuredTurn(anyString(), any(), anyString(), any(), any(), any());
    }

    @Test
    void rejectsFinalPromptSchemaAndOutputReservationBeforeAnyProviderCall() {
        var context = new com.novelagent.memory.application.ModelContextProperties();
        context.getModels().put(ModelProvider.DEEPSEEK,
                new com.novelagent.memory.application.ModelContextProperties.Capacity(60, 10));
        var bounded = new StructuredModelGateway(codex, sessions, deepSeek, runs, context);
        var largeSchema = new ObjectMapper().createObjectNode().put("description", "x".repeat(160));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bounded.request(projectId, "OUTLINE",
                ModelProvider.DEEPSEEK, "system", "user", largeSchema, "schema", 10,
                CodexSessionPolicy.NEW_THREAD)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上下文容量");
        verify(deepSeek, never()).request(anyString(), anyString(), anyString(), any(),
                org.mockito.ArgumentMatchers.anyInt(), any());
        org.mockito.Mockito.verifyNoInteractions(sessions, runs);
    }

    @Test
    void doesNotTruncatePromptWhenFinalPromptFitsAndCapturesCapacity() {
        var context = new com.novelagent.memory.application.ModelContextProperties();
        context.getModels().put(ModelProvider.DEEPSEEK,
                new com.novelagent.memory.application.ModelContextProperties.Capacity(100, 10));
        var bounded = new StructuredModelGateway(codex, sessions, deepSeek, runs, context);
        String prompt = "汉".repeat(50);
        when(deepSeek.request("schema", "system", prompt, schema, 10, deepSeekSettings))
                .thenReturn(new DeepSeekStructuredOutputClient.ResponseResult("output", null));
        assertThat(bounded.request(projectId, "OUTLINE", ModelProvider.DEEPSEEK, "system", prompt,
                schema, "schema", 10, CodexSessionPolicy.NEW_THREAD)).isEqualTo("output");
        var snapshot = org.mockito.ArgumentCaptor.forClass(RequestSnapshot.class);
        verify(runs).record(eq(projectId), eq("OUTLINE"), eq(ModelProvider.DEEPSEEK), eq("system"),
                eq(prompt), snapshot.capture(), any());
        assertThat(snapshot.getValue().maxOutputTokens()).isEqualTo(10);
        assertThat(snapshot.getValue().sessionPolicy()).isEqualTo("STATELESS");
        assertThat(snapshot.getValue().contextBudget().contextWindowTokens()).isEqualTo(100);
        assertThat(snapshot.getValue().contextBudget().estimatedInputTokens()).isGreaterThan(50);
    }

    @Test
    void outputAndSafetyReservationsAreIncludedAtCapacityBoundary() {
        var context = new com.novelagent.memory.application.ModelContextProperties();
        int input = com.novelagent.memory.application.MemoryBudgetAllocator.estimateTokens("system\n\nuser\n\n" + schema);
        var capacity = new com.novelagent.memory.application.ModelContextProperties.Capacity(input + 19, 10);
        context.getModels().put(ModelProvider.DEEPSEEK, capacity);
        var bounded = new StructuredModelGateway(codex, sessions, deepSeek, runs, context);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bounded.request(projectId, "OUTLINE",
                ModelProvider.DEEPSEEK, "system", "user", schema, "schema", 10, CodexSessionPolicy.NEW_THREAD))
                .isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(runs);
        capacity.setContextWindowTokens(input + 20);
        when(deepSeek.request("schema", "system", "user", schema, 10, deepSeekSettings))
                .thenReturn(new DeepSeekStructuredOutputClient.ResponseResult("output", null));
        assertThat(bounded.request(projectId, "OUTLINE", ModelProvider.DEEPSEEK, "system", "user", schema,
                "schema", 10, CodexSessionPolicy.NEW_THREAD)).isEqualTo("output");
    }

    private String request(String workflow, ModelProvider provider, CodexSessionPolicy policy) {
        return gateway.request(projectId, workflow, provider, "system", "user", schema,
                "schema-name", 4000, policy);
    }

    @Test
    void savedPromptIsFrozenOnceAndUsedForDeepSeekRecordingAndProviderDispatch() {
        var prompts = mock(com.novelagent.prompt.application.AgentPromptService.class);
        when(prompts.resolve("MANUSCRIPT", "original"))
                .thenReturn(new com.novelagent.prompt.application.AgentPromptService.Resolved("edited-v3", "MANUSCRIPT:v3"));
        var editable = new StructuredModelGateway(codex, sessions, deepSeek, runs,
                new com.novelagent.memory.application.ModelContextProperties(), prompts);
        when(deepSeek.request("schema", "edited-v3", "project-data", schema, 4000, deepSeekSettings))
                .thenReturn(new DeepSeekStructuredOutputClient.ResponseResult("output", null));
        assertThat(editable.request(projectId, "MANUSCRIPT", ModelProvider.DEEPSEEK, "original", "project-data",
                schema, "schema", 4000, CodexSessionPolicy.REUSE_THREAD)).isEqualTo("output");
        var snapshot = org.mockito.ArgumentCaptor.forClass(RequestSnapshot.class);
        verify(runs).record(eq(projectId), eq("MANUSCRIPT"), eq(ModelProvider.DEEPSEEK), eq("edited-v3"),
                eq("project-data"), snapshot.capture(), any());
        assertThat(snapshot.getValue().requestedMaxOutputTokens()).isEqualTo(4000);
        verify(prompts, times(1)).resolve("MANUSCRIPT", "original");
    }

    @Test
    void editedAndRestoredPromptRevisionsRotateCodexThreadsButSameRevisionCanReuse() {
        var prompts = mock(com.novelagent.prompt.application.AgentPromptService.class);
        var custom = new com.novelagent.prompt.application.AgentPromptService.Resolved("custom-v1", "OUTLINE:v1");
        var restored = new com.novelagent.prompt.application.AgentPromptService.Resolved("restored-v2", "OUTLINE:v2");
        when(prompts.resolve("OUTLINE", "original")).thenReturn(custom, custom, restored);
        var editable = new StructuredModelGateway(codex, sessions, deepSeek, runs,
                new com.novelagent.memory.application.ModelContextProperties(), prompts);
        var existing = CodexAgentSession.create(projectId, "OUTLINE", "old-default-thread");
        when(sessions.findByProjectIdAndWorkflowType(projectId, "OUTLINE")).thenReturn(Optional.of(existing));
        when(codex.startThread(projectId, "custom-v1", codexSettings)).thenReturn("custom-thread");
        when(codex.startThread(projectId, "restored-v2", codexSettings)).thenReturn("restored-thread");
        when(codex.runStructuredTurn(anyString(), eq(projectId), eq("user"), eq(schema), eq(codexSettings), any()))
                .thenReturn(new CodexAppServerClient.TurnResult("turn", "output"));
        for (int i = 0; i < 3; i++) {
            assertThat(editable.request(projectId, "OUTLINE", ModelProvider.LOCAL_CODEX, "original", "user", schema,
                    "schema", 4000, CodexSessionPolicy.REUSE_THREAD)).isEqualTo("output");
        }
        verify(codex).startThread(projectId, "custom-v1", codexSettings);
        verify(codex).resumeThread("custom-thread", projectId, "custom-v1", codexSettings);
        verify(codex).startThread(projectId, "restored-v2", codexSettings);
        assertThat(existing.matchesPromptRevision("OUTLINE:v2")).isTrue();
        assertThat(existing.getThreadId()).isEqualTo("restored-thread");
    }

    @Test
    void configuredPromptIsIncludedInContextCapacityCheckBeforeAnyModelCall() {
        var prompts = mock(com.novelagent.prompt.application.AgentPromptService.class);
        when(prompts.resolve("OUTLINE", "original")).thenReturn(
                new com.novelagent.prompt.application.AgentPromptService.Resolved("x".repeat(40000), "OUTLINE:v1"));
        var context = new com.novelagent.memory.application.ModelContextProperties();
        context.getModels().put(ModelProvider.DEEPSEEK,
                new com.novelagent.memory.application.ModelContextProperties.Capacity(1000, 100));
        var editable = new StructuredModelGateway(codex, sessions, deepSeek, runs, context, prompts);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> editable.request(projectId, "OUTLINE", ModelProvider.DEEPSEEK,
                "original", "user", schema, "schema", 100, CodexSessionPolicy.REUSE_THREAD))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("上下文容量");
        verify(deepSeek, never()).request(anyString(), anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        org.mockito.Mockito.verifyNoInteractions(runs, sessions);
    }

    @Test
    void validationIsExecutedInsideRecordedActionForBothProviders() {
        when(runs.record(any(), anyString(), any(), anyString(), anyString(), any(RequestSnapshot.class), any(), any()))
                .thenAnswer(call -> {
                    var result = ((Supplier<?>) call.getArgument(6)).get();
                    ((java.util.function.Consumer<Object>) call.getArgument(7)).accept(result);
                    return result;
                });
        when(deepSeek.request("schema-name", "system", "user", schema, 4000, deepSeekSettings))
                .thenReturn(new DeepSeekStructuredOutputClient.ResponseResult("raw", null));
        when(codex.startThread(projectId, "system", codexSettings)).thenReturn("fresh");
        when(codex.runStructuredTurn(eq("fresh"), eq(projectId), eq("user"), eq(schema), eq(codexSettings), any()))
                .thenReturn(new CodexAppServerClient.TurnResult("turn", "raw"));
        var error = new IllegalArgumentException("原文解析输出校验失败");
        for (var provider : java.util.List.of(ModelProvider.DEEPSEEK, ModelProvider.LOCAL_CODEX)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> gateway.request(projectId, "IMPORT_SOURCE_ANALYSIS",
                    provider, "system", "user", schema, "schema-name", 4000, CodexSessionPolicy.NEW_THREAD,
                    raw -> { assertThat(raw).isEqualTo("raw"); throw error; })).isSameAs(error);
        }
        verify(runs, times(2)).record(eq(projectId), eq("IMPORT_SOURCE_ANALYSIS"), any(), eq("system"), eq("user"),
                any(RequestSnapshot.class), any(), any());
    }
}
