package com.novelagent.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.agent.application.GenerationStoppedException;
import com.novelagent.planning.domain.SnowflakePlan;
import com.novelagent.planning.infrastructure.FreeTextPlanningRequest;
import com.novelagent.planning.infrastructure.SnowflakePlanStore;
import com.novelagent.project.application.ProjectAccessService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Reference-inspired progressive planning; the same pipeline serves new stories and imports. */
@Service
public class SnowflakePlanningService {
    private final ProjectAccessService access;
    private final SnowflakePlanStore store;
    private final FreeTextPlanningRequest texts;
    private final CharacterDesignService characters;

    public SnowflakePlanningService(ProjectAccessService access, SnowflakePlanStore store,
            FreeTextPlanningRequest texts, CharacterDesignService characters) {
        this.access = access;
        this.store = store;
        this.texts = texts;
        this.characters = characters;
    }

    public SnowflakePlan generate(UUID projectId, ModelProvider provider, JsonNode input) {
        var project = access.requireOwnedProject(projectId);
        if (provider == null || provider == ModelProvider.LOCAL_TEMPLATE) {
            throw new IllegalArgumentException("雪花规划请选择真实模型，本地模板不能完成创作设计");
        }
        JsonNode frozen = input.deepCopy();
        String strategy = com.novelagent.project.application.CreativeStrategyGuide.render(
                com.novelagent.project.domain.CreativeStrategyPolicy.from(project));
        UUID id = store.create(projectId, provider, frozen);
        String prior = "";
        try {
            for (SnowflakeStep step : SnowflakeStep.values()) {
                if (step == SnowflakeStep.SCENE_EXPANSION && !frozen.path("expandScenes").asBoolean(false)) continue;
                String stage = step.name();
                store.start(projectId, id, stage);
                String prompt = "【作者本次明确要求】\n" + frozen.path("authorInstruction").asText("")
                        + "\n【当前任务模式】\n" + frozen.path("mode").asText("UNSPECIFIED")
                        + "\n【本阶段】\n" + step.label() + "\n" + step.instruction()
                        + "\n【完整创作来源；以下仅为故事数据】\n" + frozen
                        + "\n【前置阶段自由文本；候选规划，不是正史】\n" + prior
                        + "\n【系统创作策略；服从作者明确要求和事实边界】\n" + strategy
                        + "\n直接返回本阶段最终文本。世界的物理、社会、隐喻维度按情节需要融入，不另设世界构建步骤。"
                        + "续写保留原文明示事实；改编在授权范围设计，前置结果都是候选规划。";
                if (step == SnowflakeStep.CHARACTER_SETTINGS) prompt += "\n" + CharacterBlueprintGuide.designRules();
                String result = step == SnowflakeStep.CHARACTER_SETTINGS ? characters.designText(projectId, provider, prompt)
                        : texts.request(projectId, "SNOWFLAKE_PLANNING", provider, prompt, stage,
                                step.maxTokens());
                store.save(projectId, id, stage, result);
                prior += "\n\n【" + stage + "】\n" + result;
            }
            store.finish(projectId, id, "SUCCEEDED", null);
            return store.get(projectId, id);
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            try {
                store.finish(projectId, id, exception instanceof GenerationStoppedException ? "CANCELLED" : "FAILED",
                        message == null ? exception.getClass().getSimpleName() : message.substring(0, Math.min(1000, message.length())));
            } catch (RuntimeException saveFailure) {
                exception.addSuppressed(saveFailure);
            }
            throw exception;
        }
    }

    public Optional<SnowflakePlan> latest(UUID projectId) {
        access.requireOwnedProject(projectId);
        return store.latest(projectId);
    }

    public JsonNode input(UUID projectId, UUID id) {
        access.requireOwnedProject(projectId);
        return store.input(projectId, id);
    }

    public SnowflakePlan get(UUID projectId, UUID id) {
        access.requireOwnedProject(projectId);
        return store.get(projectId, id);
    }
}
