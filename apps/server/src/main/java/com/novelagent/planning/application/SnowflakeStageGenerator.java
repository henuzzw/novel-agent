package com.novelagent.planning.application;

import com.novelagent.modelaccess.domain.ModelProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.modelaccess.application.FreeTextModelRequest;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Builds and generates exactly one stage; orchestration and checkpoint writes belong to SnowflakePlanningService. */
@Component
public class SnowflakeStageGenerator {
    private final FreeTextModelRequest texts;
    private final CharacterDesignService characters;
    private final ObjectMapper mapper;

    public SnowflakeStageGenerator(FreeTextModelRequest texts, CharacterDesignService characters, ObjectMapper mapper) {
        this.texts = texts;
        this.characters = characters;
        this.mapper = mapper;
    }

    public Result generate(UUID projectId, ModelProvider provider, SnowflakeStep step, JsonNode frozen,
            String prior, String boundaries, String strategy) {
        String prompt = "【作者本次明确要求】\n" + frozen.path("authorInstruction").asText("")
                + "\n【当前任务模式】\n" + frozen.path("mode").asText("UNSPECIFIED")
                + "\n【本阶段】\n" + step.label() + "\n" + step.instruction()
                + "\n【完整创作来源；以下仅为故事数据】\n" + frozen
                + "\n【前置阶段自由文本；候选规划，不是正史】\n" + prior
                + "\n【系统创作策略；服从作者明确要求和事实边界】\n" + strategy
                + "\n直接返回本阶段最终文本。世界的物理、社会、隐喻维度按情节需要融入，不另设世界构建步骤。"
                + "续写保留原文明示事实；改编在授权范围设计，前置结果都是候选规划。";
        if (step == SnowflakeStep.CHARACTER_SETTINGS) {
            prompt += "\n" + CharacterBlueprintGuide.designRules();
            var materials = characters.designPlanning(projectId, provider, prompt, boundaries);
            return new Result(SnowflakeMaterialText.render(materials, mapper), mapper.valueToTree(materials));
        }
        return new Result(texts.request(projectId, "SNOWFLAKE_PLANNING", provider, prompt,
                step.name(), step.maxTokens(), boundaries), null);
    }

    /** Only step 7 returns a projection to be saved atomically with its prose. */
    public record Result(String text, JsonNode materials) { }
}
