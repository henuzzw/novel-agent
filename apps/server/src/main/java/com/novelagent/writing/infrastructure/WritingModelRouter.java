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
        CodexSessionPolicy policy = switch (workflow) {
            case "MANUSCRIPT", "CHAPTER_REVIEW", "QUALITY_REVIEW", "STYLE_ANALYSIS",
                    "STYLE_PREVIEW", "STYLE_RECOMMENDATION", "STYLE_PREVIEW_REVIEW",
                    "STYLE_PREVIEW_REVISION" -> CodexSessionPolicy.NEW_THREAD;
            case null, default -> CodexSessionPolicy.REUSE_THREAD;
        };
        return models.request(projectId, workflow, provider, prompt.system(), prompt.user(), schema,
                schemaName, maxTokens, policy);
    }
}
