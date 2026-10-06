package com.novelagent.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.CodexAppServerClient;
import com.novelagent.project.domain.GlobalModelSettings;
import org.junit.jupiter.api.Test;

class GlobalModelCatalogServiceTest {
    private final CodexAppServerClient codex = mock(CodexAppServerClient.class);
    private final GlobalModelCatalogService service = new GlobalModelCatalogService(codex);

    @Test void readsRuntimeCatalogAndRejectsUnsupportedModelEffortPairs() throws Exception {
        when(codex.listModels()).thenReturn(new ObjectMapper().readTree("""
                [{"model":"gpt-6.1-sol","displayName":"GPT-6.1 Sol","defaultReasoningEffort":"high",
                  "supportedReasoningEfforts":[{"reasoningEffort":"high"},{"reasoningEffort":"max"}]},
                 {"model":"hidden","hidden":true}]
                """));
        assertThat(service.chatGptModels()).hasSize(1);
        var value = new GlobalModelSettings(ModelProvider.LOCAL_CODEX, "gpt-6.1-sol", "max", "deepseek-flash", 0);
        assertThat(service.validate(value)).isSameAs(value);
        assertThatThrownBy(() -> service.validate(new GlobalModelSettings(ModelProvider.LOCAL_CODEX,
                "gpt-6.1-sol", "low", "deepseek-flash", 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.validate(new GlobalModelSettings(ModelProvider.LOCAL_CODEX,
                "unknown-model", "high", "deepseek-flash", 0))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void switchingToDeepSeekDoesNotRequireChatGptToBeOnline() {
        service.validate(new GlobalModelSettings(ModelProvider.DEEPSEEK, "gpt-6-sol", "high", "deepseek-v4-pro", 0));
        verifyNoInteractions(codex);
    }
}
