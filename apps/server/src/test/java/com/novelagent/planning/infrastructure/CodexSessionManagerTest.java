package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.planning.application.ModelProvider;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class CodexSessionManagerTest {
    private final UUID projectId = UUID.randomUUID();
    private final CodexAppServerClient codex = mock(CodexAppServerClient.class);
    private final CodexAgentSessionRepository sessions = mock(CodexAgentSessionRepository.class);
    private final CodexSessionManager manager = new CodexSessionManager(codex, sessions);
    private final EffectiveSettings settings = new EffectiveSettings(ModelProvider.LOCAL_CODEX, "test-model", "high", 3L);

    @Test
    void failedTurnDoesNotOverwriteThePreviousCompletedTurnOrSaveAgain() {
        var session = CodexAgentSession.create(projectId, "OUTLINE", "thread");
        session.recordTurn("previous-turn");
        when(sessions.findByProjectIdAndWorkflowType(projectId, "OUTLINE")).thenReturn(Optional.of(session));
        var schema = new ObjectMapper().createObjectNode();
        var failure = new CodexAppServerException("connection lost");
        when(codex.runStructuredTurn(eq("thread"), eq(projectId), eq("user"), eq(schema), eq(settings), any()))
                .thenThrow(failure);

        assertThatThrownBy(() -> manager.run(projectId, "OUTLINE", "system", "user", schema,
                CodexSessionPolicy.REUSE_THREAD, settings, null, output -> { }))
                .isSameAs(failure);
        assertThat(session).extracting("lastTurnId").isEqualTo("previous-turn");
        verify(sessions, never()).saveAndFlush(any());
    }

    @Test
    void ordinaryResumeFailuresAreNotRetriedAsMissingThreads() {
        var session = CodexAgentSession.create(projectId, "OUTLINE", "thread");
        when(sessions.findByProjectIdAndWorkflowType(projectId, "OUTLINE")).thenReturn(Optional.of(session));
        var failure = new CodexAppServerException("network unavailable");
        doThrow(failure).when(codex).resumeThread("thread", projectId, "system", settings);

        assertThatThrownBy(() -> manager.run(projectId, "OUTLINE", "system", "user",
                new ObjectMapper().createObjectNode(), CodexSessionPolicy.REUSE_THREAD, settings, null, output -> { }))
                .isSameAs(failure);
        verify(codex, never()).startThread(any(), anyString(), any());
        verify(codex, never()).runStructuredTurn(anyString(), any(), anyString(), any(), any(), any());
    }

    @Test
    void forwardsTheSameProgressObserverAndSavesTheCompletedTurn() {
        var session = CodexAgentSession.create(projectId, "OUTLINE", "thread");
        when(sessions.findByProjectIdAndWorkflowType(projectId, "OUTLINE")).thenReturn(Optional.of(session));
        var schema = new ObjectMapper().createObjectNode();
        Consumer<String> progress = output -> { };
        var result = new CodexAppServerClient.TurnResult("current-turn", "output");
        when(codex.runStructuredTurn("thread", projectId, "user", schema, settings, progress)).thenReturn(result);

        assertThat(manager.run(projectId, "OUTLINE", "system", "user", schema,
                CodexSessionPolicy.REUSE_THREAD, settings, null, progress)).isSameAs(result);
        assertThat(session).extracting("lastTurnId").isEqualTo("current-turn");
        verify(sessions).saveAndFlush(session);
    }
}
