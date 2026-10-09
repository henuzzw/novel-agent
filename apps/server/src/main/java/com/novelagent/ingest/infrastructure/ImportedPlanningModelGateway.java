package com.novelagent.ingest.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ImportedPlanningModelGateway {
    private final StructuredModelGateway models;

    public ImportedPlanningModelGateway(StructuredModelGateway models) {
        this.models = models;
    }

    public String request(UUID projectId, String workflow, ModelProvider provider, String systemPrompt,
            String userPrompt, JsonNode schema, String schemaName, int maxTokens) {
        return models.request(projectId, workflow, provider, systemPrompt, userPrompt, schema,
                schemaName, maxTokens, CodexSessionPolicy.REUSE_THREAD);
    }

    public String request(UUID projectId, String workflow, ModelProvider provider, String systemPrompt,
            String userPrompt, JsonNode schema, String schemaName, int maxTokens, java.util.function.Consumer<String> consume) {
        return models.request(projectId, workflow, provider, systemPrompt, userPrompt, schema,
                schemaName, maxTokens, CodexSessionPolicy.REUSE_THREAD, consume);
    }
}
