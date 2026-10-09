package com.novelagent.modelaccess.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.modelaccess.infrastructure.ChatGptResponsesClient;
import com.novelagent.modelaccess.infrastructure.HttpConversationStore;
import com.novelagent.modelaccess.infrastructure.PrivateOAuthStore;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.project.application.GlobalModelSettingsService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChatGptDirectGatewayTest {
    @Test void unsuccessfulDecodeReleasesLeaseWithoutPersistingPartialHistory() {
        var json = new ObjectMapper(); var auth = mock(ChatGptOAuthService.class); var store = mock(HttpConversationStore.class);
        when(auth.authorization()).thenReturn(new PrivateOAuthStore.Credentials("oaiapp_test", "subject", null, null, "test", "refresh", 1, "chatgpt.tokens.use.direct"));
        var history = json.createArrayNode(); history.addObject().put("role", "user").put("content", "previous input");
        var lease = new HttpConversationStore.Lease(UUID.randomUUID(), "STORY_PLANNING", UUID.randomUUID(), UUID.randomUUID(), history);
        when(store.claim(any(), anyString(), anyString(), anyString(), anyBoolean())).thenReturn(lease);
        var gateway = new ChatGptDirectGateway(auth, store, mock(ChatGptResponsesClient.class), mock(GlobalModelSettingsService.class), new ModelContextProperties());
        try (var prepared = gateway.prepare(lease.project(), lease.workflow(), "updated instructions", "current input", CodexSessionPolicy.REUSE_THREAD, "revision", 1000)) {
            assertThat(prepared.wireInput()).contains("previous input", "current input");
        }
        verify(store).release(lease); verify(store, never()).complete(any(), any()); assertThat(history).hasSize(1);
        try (var prepared = gateway.prepare(lease.project(), lease.workflow(), "updated instructions", "current input", CodexSessionPolicy.REUSE_THREAD, "revision", 1000)) {
            var output = json.createArrayNode(); output.addObject().put("type", "message").put("role", "assistant");
            prepared.accept(new ChatGptResponsesClient.Result("response", "final", null, output));
        }
        var saved = org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(store).complete(eq(lease), saved.capture()); assertThat(saved.getValue().size()).isEqualTo(3);
        verify(store, times(1)).release(lease);
    }
}
