package com.novelagent.planning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.StoryBibleOutputSchema;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import com.novelagent.prompt.application.AgentPromptDefaults;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Projects Snowflake step 7 through the single active character-design Agent. */
@Service
public class CharacterDesignService {
    private final StructuredModelGateway models;
    private final ObjectMapper mapper;
    private final StoryBibleOutputSchema schemas;
    public CharacterDesignService(StructuredModelGateway models, ObjectMapper mapper, StoryBibleOutputSchema schemas) {
        this.models = models;
        this.mapper = mapper;
        this.schemas = schemas;
    }

    /** Step 7 also projects already designed rules and promises for the data pages. Provider output stays plain text. */
    public StoryBibleContent designPlanning(UUID projectId, ModelProvider provider,
            String prompt, String frozenBoundaries) {
        if (provider == null || provider == ModelProvider.LOCAL_TEMPLATE) {
            throw new IllegalArgumentException("人物设计请选择真实模型");
        }
        var schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("materials");
        schema.putObject("properties").set("materials", schemas.value().at("/properties/content"));
        var input = mapper.createObjectNode().put("stage", "CHARACTER_SETTINGS").put("creativeData", prompt)
                .put("creativeBoundarySnapshot", frozenBoundaries)
                .put("execution", "执行雪花第7步，完善人物并整理前6步已有世界规则、秘密、初始关系和明确伏笔承诺。"
                        + "这不是独立圣经任务，不另起故事，不新增核心剧情；硬约束只来自作者明确限制。"
                        + "按本轮纯文本保存标题交付完整人物和资料，未提供的事实保持未知，未来计划不是正史。");
        try {
            var root = mapper.readTree(models.request(projectId, "CHARACTER_DESIGN", provider,
                    AgentPromptDefaults.system("CHARACTER_DESIGN"), input.toString(), schema,
                    "snowflake_character_materials", 12000, CodexSessionPolicy.REUSE_THREAD));
            if (root == null || !root.path("materials").isObject()) {
                throw new IllegalArgumentException("雪花第7步没有返回完整资料");
            }
            var materials = mapper.treeToValue(root.path("materials"), StoryBibleContent.class);
            if (materials == null || materials.characterBlueprints().isEmpty()) {
                throw new IllegalArgumentException("雪花第7步没有返回人物档案");
            }
            return materials;
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("雪花第7步资料无法保存", failure);
        }
    }

}
