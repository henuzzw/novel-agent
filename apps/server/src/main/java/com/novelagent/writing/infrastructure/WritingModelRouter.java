package com.novelagent.writing.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class WritingModelRouter {
    private final StructuredModelGateway models;

    WritingModelRouter(StructuredModelGateway models) {
        this.models = models;
    }

    String request(UUID projectId, String workflow, ModelProvider provider,
            WritingPromptFactory.Prompt prompt, JsonNode schema, String schemaName, int maxTokens) {
        CodexSessionPolicy policy = "MANUSCRIPT".equals(workflow) || "CHAPTER_CONTRACT".equals(workflow)
                || "CHAPTER_CONTRACT_REVIEW".equals(workflow) || "QUALITY_REVIEW".equals(workflow)
                || "STYLE_ANALYSIS".equals(workflow) || "STYLE_PREVIEW".equals(workflow)
                || "STYLE_RECOMMENDATION".equals(workflow) || "STYLE_PREVIEW_REVIEW".equals(workflow)
                || "STYLE_PREVIEW_REVISION".equals(workflow)
                        ? CodexSessionPolicy.NEW_THREAD : CodexSessionPolicy.REUSE_THREAD;
        return models.request(projectId, workflow, provider, prompt.system(), prompt.user(), schema,
                schemaName, maxTokens, policy);
    }
}
