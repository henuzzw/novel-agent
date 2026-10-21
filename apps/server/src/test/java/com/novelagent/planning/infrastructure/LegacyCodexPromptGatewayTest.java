package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.planning.application.ModelProvider;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LegacyCodexPromptGatewayTest {
    @Test void existingThreadWithoutToolReceivesFullSourceInsteadOfUnreadableReference() {
        var client = mock(CodexAppServerClient.class);
        var repository = mock(CodexAgentSessionRepository.class);
        var project = UUID.randomUUID();
        when(repository.findByProjectIdAndWorkflowType(project, "planning"))
                .thenReturn(Optional.of(CodexAgentSession.create(project, "planning", "old-thread")));
        String source = "完整来源".repeat(3000);
        String prompt = "【本轮创作资料】\n故事内容\n" + source;
        var gateway = new LegacyCodexPromptGateway(client, repository, new ObjectMapper(), new ModelContextProperties());
        try (var request = gateway.prepare(project, "BOOK_TITLE", "planning", "system", prompt,
                CodexSessionPolicy.REUSE_THREAD, new EffectiveSettings(ModelProvider.LOCAL_CODEX, "model", "high", 1L), null)) {
            assertThat(request.wireInput()).isEqualTo(prompt).doesNotContain("material_id=");
            assertThat(request.instructions("system")).contains("资料已完整内联");
        }
        verify(client, never()).openMaterialScope(anyString(), any(), any());
        verify(client, never()).startThread(any(), anyString(), any());
    }

    @Test void registeredToolIsScopedBeforeGenerationAndReleasedAfterwards() {
        var client = mock(CodexAppServerClient.class);
        var repository = mock(CodexAgentSessionRepository.class);
        var project = UUID.randomUUID();
        when(repository.findByProjectIdAndWorkflowType(project, "planning"))
                .thenReturn(Optional.of(CodexAgentSession.create(project, "planning", "tool-thread")));
        when(client.supportsMaterialTool("tool-thread")).thenReturn(true);
        String source = "完整来源".repeat(3000);
        String prompt = "【本轮创作资料】\n故事内容\n" + source;
        var gateway = new LegacyCodexPromptGateway(client, repository, new ObjectMapper(), new ModelContextProperties());
        try (var request = gateway.prepare(project, "BOOK_TITLE", "planning", "system", prompt,
                CodexSessionPolicy.REUSE_THREAD, new EffectiveSettings(ModelProvider.LOCAL_CODEX, "model", "high", 1L), null)) {
            assertThat(request.wireInput()).contains("material_id=").doesNotContain(source);
        }
        var order = inOrder(client);
        order.verify(client).saveMaterialFiles(eq(project), any());
        order.verify(client).openMaterialScope(eq("tool-thread"), eq(project), any());
        order.verify(client).closeMaterialScope("tool-thread");
    }

    @Test void workflowsShareProjectLockAndCompactionCompletesBeforeGeneration() {
        var client = mock(CodexAppServerClient.class);
        var repository = mock(CodexAgentSessionRepository.class);
        var project = UUID.randomUUID();
        var session = CodexAgentSession.create(project, "planning", "existing-thread");
        when(repository.findByProjectIdAndWorkflowType(project, "planning")).thenReturn(Optional.of(session));
        when(client.contextTokens("existing-thread")).thenReturn(125000L);
        var settings = new EffectiveSettings(ModelProvider.LOCAL_CODEX, "model", "high", 1L);
        var gateway = new LegacyCodexPromptGateway(client, repository, new ObjectMapper(), new ModelContextProperties());
        try (var request = gateway.prepare(project, "OUTLINE", "planning", "system", "user",
                CodexSessionPolicy.REUSE_THREAD, settings, null)) {
            assertThatThrownBy(() -> gateway.prepare(project, "STORY_BIBLE", "planning", "system", "user",
                    CodexSessionPolicy.REUSE_THREAD, settings, null)).hasMessageContaining("正在生成");
            request.run(request.instructions("system"), settings, 4000, null, ignored -> {});
        }
        var order = inOrder(client);
        order.verify(client).compactThread("existing-thread");
        order.verify(client).runStructuredTurn(eq("existing-thread"), eq(project), eq("user"), isNull(), eq(settings), any());
        try (var request = gateway.prepare(project, "STORY_BIBLE", "planning", "system", "user",
                CodexSessionPolicy.REUSE_THREAD, settings, null)) {
            // Closing after preflight, without a model request, also releases the project lock.
        }
        verify(client, never()).startThread(any(), anyString(), any());
    }
}
