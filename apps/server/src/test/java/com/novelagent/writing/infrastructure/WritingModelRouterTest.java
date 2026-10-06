package com.novelagent.writing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WritingModelRouterTest {
    @Test
    void independentWritingWorkflowsUseFreshCodexThreads() {
        assertPolicy("MANUSCRIPT", CodexSessionPolicy.NEW_THREAD);
        assertPolicy("CHAPTER_CONTRACT", CodexSessionPolicy.NEW_THREAD);
        assertPolicy("CHAPTER_CONTRACT_REVIEW", CodexSessionPolicy.NEW_THREAD);
        assertPolicy("STYLE_RECOMMENDATION", CodexSessionPolicy.NEW_THREAD);
        assertPolicy("STYLE_PREVIEW_REVIEW", CodexSessionPolicy.NEW_THREAD);
        assertPolicy("STYLE_PREVIEW_REVISION", CodexSessionPolicy.NEW_THREAD);
    }

    @Test
    void conversationalReviewWorkflowReusesItsCodexThread() {
        assertPolicy("REVIEW", CodexSessionPolicy.REUSE_THREAD);
    }

    private void assertPolicy(String workflow, CodexSessionPolicy expectedPolicy) {
        UUID projectId = UUID.randomUUID();
        StructuredModelGateway models = mock(StructuredModelGateway.class);
        WritingModelRouter router = new WritingModelRouter(models);
        WritingPromptFactory.Prompt prompt = new WritingPromptFactory.Prompt("系统", "选定的基准正文");
        JsonNode schema = new ObjectMapper().createObjectNode();
        when(models.request(projectId, workflow, ModelProvider.LOCAL_CODEX,
                prompt.system(), prompt.user(), schema, "manuscript", 5000, expectedPolicy))
                .thenReturn("{}");

        String result = router.request(projectId, workflow, ModelProvider.LOCAL_CODEX,
                prompt, schema, "manuscript", 5000);

        assertThat(result).isEqualTo("{}");
        verify(models).request(projectId, workflow, ModelProvider.LOCAL_CODEX,
                prompt.system(), prompt.user(), schema, "manuscript", 5000, expectedPolicy);
    }
}
