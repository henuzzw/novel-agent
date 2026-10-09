package com.novelagent.planning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.CharacterBlueprint;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.StoryBibleOutputSchema;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import com.novelagent.prompt.application.AgentPromptDefaults;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** One character-design Agent, shared by import planning, missing-field completion and preparation. */
@Service
public class CharacterDesignService {
    private final StructuredModelGateway models;
    private final ObjectMapper mapper;
    private final StoryBibleOutputSchema schemas;
    private final com.novelagent.planning.infrastructure.FreeTextPlanningRequest texts;

    public CharacterDesignService(StructuredModelGateway models, ObjectMapper mapper, StoryBibleOutputSchema schemas, com.novelagent.planning.infrastructure.FreeTextPlanningRequest texts) {
        this.models = models;
        this.mapper = mapper;
        this.schemas = schemas;
        this.texts = texts;
    }

    /** Natural prose for progressive planning; no drive-triangle or arc-field validator. */
    public String designText(UUID projectId, ModelProvider provider, String prompt) {
        return texts.request(projectId, "CHARACTER_DESIGN", provider, prompt, "CHARACTERS", 10000);
    }

    public List<CharacterBlueprint> design(UUID projectId, ModelProvider provider, JsonNode input) {
        var schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("characterBlueprints");
        schema.putObject("properties").set("characterBlueprints",
                schemas.value().at("/properties/content/properties/characterBlueprints"));
        try {
            var root = mapper.readTree(request(projectId, provider, input, schema, "character_design", 10000));
            if (root == null || !root.isObject() || root.size() != 1 || !root.path("characterBlueprints").isArray()) {
                throw new IllegalArgumentException("人物设计输出结构不合法");
            }
            List<CharacterBlueprint> result = mapper.convertValue(root.get("characterBlueprints"), new TypeReference<>() { });
            if (result.isEmpty() || result.size() > 12 || result.stream().anyMatch(java.util.Objects::isNull)
                    || result.stream().map(CharacterBlueprint::name).distinct().count() != result.size()) {
                throw new IllegalArgumentException("人物设计未返回有效且姓名唯一的蓝图");
            }
            return List.copyOf(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("人物设计输出格式不合法", exception);
        }
    }

    /** Different output schemas are modes of this same Agent, not separate character planners. */
    public String request(UUID projectId, ModelProvider provider, JsonNode input, JsonNode schema, String schemaName, int maxTokens) {
        if (provider == null || provider == ModelProvider.LOCAL_TEMPLATE) {
            throw new IllegalArgumentException("人物设计请选择真实模型，本地模板不能生成人物设定");
        }
        return models.request(projectId, "CHARACTER_DESIGN", provider, AgentPromptDefaults.system("CHARACTER_DESIGN"),
                input.toString(), schema, schemaName, maxTokens, CodexSessionPolicy.NEW_THREAD);
    }
}
