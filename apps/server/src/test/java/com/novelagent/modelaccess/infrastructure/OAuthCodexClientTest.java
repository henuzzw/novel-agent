package com.novelagent.modelaccess.infrastructure;

import com.novelagent.platform.support.Sha256;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.modelaccess.application.ChatGptOAuthService;
import com.novelagent.project.application.GlobalModelSettingsService;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OAuthCodexClientTest {
    @Test void passesAuthorizationOnlyInEnvironmentAndLimitsToolToCurrentTask(@TempDir Path root) {
        var json = new ObjectMapper(); var store = mock(CodexConversationStore.class); var auth = mock(ChatGptOAuthService.class);
        when(auth.authorization()).thenReturn(new PrivateOAuthStore.Credentials("test-client", "test-subject", null, null,
                "private-test-token", "private-refresh", Long.MAX_VALUE, "chatgpt.tokens.use.direct"));
        var client = new OAuthCodexClient(json, store, auth, mock(GlobalModelSettingsService.class), new ModelContextProperties(),
                "codex.exe", "test-model", "high", 5, 5, root.toString(), "");
        String binding = client.authorize();
        assertThat(client.launchCommand().toString()).contains("supports_websockets=false", "env_key=\"ACCESS_TOKEN\"", "features.shell_tool=false")
                .doesNotContain("private-test-token", "private-refresh");
        var environment = new HashMap<String, String>(); environment.put("OPENAI_API_KEY", "not-used");
        client.configureEnvironment(environment);
        assertThat(environment).containsEntry("ACCESS_TOKEN", "private-test-token").doesNotContainKey("OPENAI_API_KEY");
        String content = "原文".repeat(6000); String id = Sha256.ofUtf8(content);
        var lease = new CodexConversationStore.Lease(UUID.randomUUID(), "NOVEL_MAIN", UUID.randomUUID(), "thread", Set.of(), 0);
        client.openScope("thread", lease, Set.of(id), binding); when(store.readMaterial(lease, id)).thenReturn(content);
        var params = json.createObjectNode().put("threadId", "thread").put("turnId", "turn").put("tool", "novel_read_material");
        params.putObject("arguments").put("material_id", id).put("offset", 0);
        var response = client.handleDynamicTool(params);
        assertThat(response.path("success").asBoolean()).isTrue();
        assertThat(response.at("/contentItems/0/text").asText()).contains("next_offset=6000", content.substring(0, 6000));
        params.withObject("/arguments").put("material_id", "another-project");
        assertThat(client.handleDynamicTool(params).path("success").asBoolean()).isFalse();
        verify(store, times(1)).readMaterial(any(), any());
        client.closeScope("thread"); client.close();
    }
}
