package com.novelagent.modelaccess.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.modelaccess.infrastructure.CodexConversationStore;
import com.novelagent.modelaccess.infrastructure.CodexMaterialDelta;
import com.novelagent.modelaccess.infrastructure.OAuthCodexClient;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.CodexAppServerClient;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CodexProjectGatewayTest {
    @Test void reportSchemaReachesOAuthCodexTurnWithoutChangingMaterialScopeOrThreadPolicy() throws Exception {
        var codex = mock(OAuthCodexClient.class); var store = mock(CodexConversationStore.class);
        var project = UUID.randomUUID(); var lease = new CodexConversationStore.Lease(project, "QUALITY_REVIEW", UUID.randomUUID(), null, Set.of(), 0);
        when(codex.authorize()).thenReturn("account"); when(store.claim(project, "QUALITY_REVIEW", "account", true)).thenReturn(lease);
        when(codex.startThread(eq(project), anyString(), any())).thenReturn("new-review-thread");
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"type\":\"object\",\"additionalProperties\":false,\"required\":[],\"properties\":{}}");
        var settings = new EffectiveSettings(ModelProvider.LOCAL_CODEX, "test", "high", 1L);
        when(codex.runStructuredTurn(eq("new-review-thread"), eq(project), anyString(), eq(schema), eq(settings), any()))
                .thenReturn(new CodexAppServerClient.TurnResult("turn", "{}"));
        var gateway = new CodexProjectGateway(codex, store, new ModelContextProperties());
        try (var request = gateway.prepare(project, "QUALITY_REVIEW", true, "input")) {
            assertThat(request.run("rules", settings, 1000, schema, ignored -> {}).output()).isEqualTo("{}"); request.accept();
        }
        verify(codex).runStructuredTurn(eq("new-review-thread"), eq(project), eq("input"), eq(schema), eq(settings), any());
        verify(store).complete(eq(lease), anySet(), anyLong());
    }
    @Test void sameThreadUpdatesRoleCompactsAndSendsDeltaWithoutHistory() {
        var codex = mock(OAuthCodexClient.class); var store = mock(CodexConversationStore.class);
        var project = UUID.randomUUID(); String source = "准确原文".repeat(400);
        var lease = new CodexConversationStore.Lease(project, "NOVEL_MAIN", UUID.randomUUID(), "saved-thread",
                Set.of(CodexMaterialDelta.fingerprint(source)), 120000);
        when(codex.authorize()).thenReturn("account");
        when(store.claim(project, "NOVEL_MAIN", "account", false)).thenReturn(lease);
        var selected = new EffectiveSettings(ModelProvider.LOCAL_CODEX, "test-model", "high", 1L);
        when(codex.runStructuredTurn(anyString(), eq(project), anyString(), isNull(), eq(selected), any()))
                .thenReturn(new CodexAppServerClient.TurnResult("turn", "正文"));
        var gateway = new CodexProjectGateway(codex, store, new ModelContextProperties());
        try (var request = gateway.prepare(project, "NOVEL_MAIN", false, "【本轮创作资料】\n【原文】\n" + source)) {
            assertThat(request.wireInput()).doesNotContain(source);
            assertThatThrownBy(() -> gateway.prepare(project, "NOVEL_MAIN", false, "another")).hasMessageContaining("正在执行");
            assertThat(request.run("新的本轮角色", selected, 12000, ignored -> {}).output()).isEqualTo("正文");
            request.accept();
        }
        var order = inOrder(codex);
        order.verify(codex).resumeThread("saved-thread", project, "新的本轮角色", selected);
        order.verify(codex).openScope(eq("saved-thread"), eq(lease), anySet(), eq("account"));
        order.verify(codex).compactThread("saved-thread");
        order.verify(codex).runStructuredTurn(eq("saved-thread"), eq(project), anyString(), isNull(), eq(selected), any());
        verify(store).complete(eq(lease), anySet(), anyLong()); verify(store, never()).abort(any());
    }
    @Test void invalidOrStoppedTurnDiscardsItsBindingButKeepsMaterialStore() {
        var codex = mock(OAuthCodexClient.class); var store = mock(CodexConversationStore.class);
        var project = UUID.randomUUID(); var lease = new CodexConversationStore.Lease(project, "NOVEL_MAIN", UUID.randomUUID(), null, Set.of(), 0);
        when(codex.authorize()).thenReturn("account"); when(store.claim(any(), any(), any(), anyBoolean())).thenReturn(lease);
        var gateway = new CodexProjectGateway(codex, store, new ModelContextProperties());
        when(codex.startThread(any(), any(), any())).thenThrow(new IllegalStateException("request rejected"));
        try (var request = gateway.prepare(project, "NOVEL_MAIN", false, "material")) {
            assertThatThrownBy(() -> request.run("system", new EffectiveSettings(ModelProvider.LOCAL_CODEX, "test", "high", 1L), 100, ignored -> {}))
                    .hasMessageContaining("request rejected");
        }
        verify(store).abort(lease); verify(store, never()).complete(any(), any(), anyLong());
    }
}
