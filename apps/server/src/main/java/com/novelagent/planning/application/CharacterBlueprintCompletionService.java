package com.novelagent.planning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.api.StoryBibleResponse;
import com.novelagent.planning.domain.CharacterBlueprint;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.StoryBibleOutputSchema;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 人物蓝图补全。
 *
 * <p>读取指定圣经版本，在事务外请求模型补齐人物蓝图，再复核来源保存新草稿。只补空白及缺失人物，不覆盖作者已有完整设定，不自动发布。</p>
 */
@Service
public class CharacterBlueprintCompletionService {
    private final CharacterBlueprintDraftStore drafts;
    private final StructuredModelGateway models;
    private final ObjectMapper mapper;
    private final StoryBibleOutputSchema schemas;

    public CharacterBlueprintCompletionService(CharacterBlueprintDraftStore drafts, StructuredModelGateway models,
            ObjectMapper mapper, StoryBibleOutputSchema schemas) {
        this.drafts = drafts; this.models = models; this.mapper = mapper; this.schemas = schemas;
    }

    /**
     * 在指定圣经来源上请求人物补全候选，完成后复核来源并保存新草稿；不自动发布。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param bibleId 故事圣经版本 ID。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     * @param provider 明确选择的生成供应商；本地模板不代表真实文学生成。
     * @param instruction 作者本次要求，只能在已有事实与授权边界内执行。
     */
    public StoryBibleResponse complete(UUID projectId, UUID bibleId, long expectedVersion,
            ModelProvider provider, String instruction) {
        if (provider == null || provider == ModelProvider.LOCAL_TEMPLATE) {
            throw new IllegalArgumentException("人物补全请选择真实模型，本地模板不能生成人物设定");
        }
        var source = drafts.load(projectId, bibleId, expectedVersion);
        try {
            var schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
            schema.putArray("required").add("characterBlueprints");
            schema.putObject("properties").set("characterBlueprints",
                    schemas.value().at("/properties/content/properties/characterBlueprints"));
            var input = mapper.createObjectNode();
            input.set("storyBible", mapper.valueToTree(source.rendered()));
            input.put("authorInstruction", instruction == null ? "" : instruction);
            String raw = models.request(projectId, "CHARACTER_BLUEPRINT_COMPLETION", provider,
                    com.novelagent.prompt.application.AgentPromptDefaults.system("CHARACTER_BLUEPRINT_COMPLETION"),
                    input.toString(), schema, "character_blueprint_completion", 8000, CodexSessionPolicy.NEW_THREAD);
            var root = mapper.readTree(raw);
            if (!root.isObject() || root.size() != 1 || !root.path("characterBlueprints").isArray()) {
                throw new IllegalArgumentException("人物补全模型输出结构不合法");
            }
            List<CharacterBlueprint> proposed = mapper.convertValue(root.get("characterBlueprints"),
                    new TypeReference<List<CharacterBlueprint>>() { });
            if (proposed.isEmpty() || proposed.size() > 12 || proposed.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("模型未返回有效人物底稿");
            }
            return drafts.save(source, proposed, provider, instruction);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("人物补全模型输出格式不合法", exception);
        }
    }
}
