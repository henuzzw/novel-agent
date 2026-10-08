package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.prompt.application.AgentPromptDefaults;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Free prose uses a single transport field, not a literary schema or completeness validator. */
@Component
public class FreeTextPlanningRequest {
    private final StructuredModelGateway models;
    private final ObjectMapper mapper;

    public FreeTextPlanningRequest(StructuredModelGateway models, ObjectMapper mapper) {
        this.models = models;
        this.mapper = mapper;
    }

    public String request(UUID projectId, String workflow, ModelProvider provider,
            String prompt, String stage, int maxTokens) {
        var schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("text");
        schema.putObject("properties").putObject("text").put("type", "string");
        var text = new AtomicReference<String>();
        models.request(projectId, workflow, provider, AgentPromptDefaults.system(workflow), prompt,
                schema, "snowflake_" + stage.toLowerCase(java.util.Locale.ROOT), maxTokens,
                CodexSessionPolicy.NEW_THREAD, raw -> text.set(readText(raw)));
        return text.get();
    }

    private String readText(String raw) {
        try {
            var root = mapper.readTree(raw);
            if (root == null || !root.path("text").isTextual() || root.path("text").asText().isBlank()) {
                throw new IllegalArgumentException("雪花规划未返回非空文本");
            }
            return root.path("text").asText().trim();
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("雪花规划响应无法读取", exception);
        }
    }
}
