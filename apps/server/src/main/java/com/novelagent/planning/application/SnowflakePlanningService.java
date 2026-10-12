package com.novelagent.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.agent.application.GenerationStoppedException;
import com.novelagent.planning.domain.SnowflakePlan;
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
    private final SnowflakeStageGenerator stages;

    public SnowflakePlanningService(ProjectAccessService access, SnowflakePlanStore store,
            SnowflakeStageGenerator stages) {
        this.access = access;
        this.store = store;
        this.stages = stages;
    }

    public SnowflakePlan generate(UUID projectId, ModelProvider provider, JsonNode input) {
        var project = access.requireOwnedProject(projectId);
        if (provider == null || provider == ModelProvider.LOCAL_TEMPLATE) {
            throw new IllegalArgumentException("雪花规划请选择真实模型，本地模板不能完成创作设计");
        }
        JsonNode frozen = input.deepCopy();
        String boundaries = com.novelagent.project.application.CreativeBoundaryGuide.render(
                com.novelagent.project.domain.CreativeBoundaries.from(project));
        ((com.fasterxml.jackson.databind.node.ObjectNode) frozen).put("creativeBoundarySnapshot", boundaries);
        String strategy = com.novelagent.project.application.CreativeStrategyGuide.render(
                com.novelagent.project.domain.CreativeStrategyPolicy.from(project));
        ((com.fasterxml.jackson.databind.node.ObjectNode) frozen).put("creativeStrategySnapshot", strategy);
        UUID id = store.create(projectId, provider, frozen);
        return execute(projectId, id, provider, frozen, java.util.Map.of(), boundaries, strategy);
    }

    /** Resume only a saved prefix; the interrupted response is discarded and requested again. */
    public SnowflakePlan resume(UUID projectId, UUID id) {
        var project = access.requireOwnedProject(projectId);
        var plan = store.get(projectId, id);
        if (!java.util.Set.of("FAILED", "CANCELLED", "SUCCEEDED").contains(plan.status())) {
            throw new IllegalArgumentException("该雪花规划正在生成，不能重复继续");
        }
        var frozen = store.input(projectId, id);
        String boundaries = com.novelagent.project.application.CreativeBoundaryGuide.render(
                com.novelagent.project.domain.CreativeBoundaries.from(project));
        if (!boundaries.equals(frozen.path("creativeBoundarySnapshot").asText())) {
            throw new IllegalArgumentException("世界设定或改编幅度已改变，请重新生成雪花底稿");
        }
        String strategy = com.novelagent.project.application.CreativeStrategyGuide.render(
                com.novelagent.project.domain.CreativeStrategyPolicy.from(project));
        if (frozen.has("creativeStrategySnapshot") && !strategy.equals(frozen.path("creativeStrategySnapshot").asText())) {
            throw new IllegalArgumentException("创作策略已改变，请重新生成雪花底稿");
        }
        var completed = new java.util.LinkedHashMap<>(plan.steps());
        var invalid = plan.invalidSteps();
        if (!invalid.isEmpty()) {
            if (plan.status().equals("SUCCEEDED")) throw new IllegalArgumentException("已完成规划包含资料读取失败说明，请重新生成规划，不沿用无效底稿");
            invalid.keySet().forEach(completed::remove);
        }
        boolean missing = false;
        for (var step : SnowflakeStep.values()) {
            if (step == SnowflakeStep.SCENE_EXPANSION && !frozen.path("expandScenes").asBoolean(false)) continue;
            String text = completed.get(step.name());
            if (text == null || text.isBlank()) missing = true;
            else if (missing) throw new IllegalArgumentException("雪花步骤记录不连续，请重新生成");
        }
        if (plan.status().equals("SUCCEEDED")) {
            if (missing) throw new IllegalArgumentException("已完成规划的步骤不完整，请重新生成");
            return plan;
        }
        if (invalid.isEmpty()) store.resume(projectId, id);
        else store.resumeWithValidPrefix(projectId, id, plan.steps(), completed);
        return execute(projectId, id, plan.provider(), frozen, completed, boundaries, strategy);
    }

    private SnowflakePlan execute(UUID projectId, UUID id, ModelProvider provider, JsonNode frozen,
            java.util.Map<String, String> completed, String boundaries, String strategy) {
        String prior = "";
        try {
            for (SnowflakeStep step : SnowflakeStep.values()) {
                if (step == SnowflakeStep.SCENE_EXPANSION && !frozen.path("expandScenes").asBoolean(false)) continue;
                String stage = step.name();
                String saved = completed.get(stage);
                if (saved != null && !saved.isBlank()) {
                    prior += "\n\n【" + stage + "】\n" + saved;
                    continue;
                }
                store.start(projectId, id, stage);
                var result = stages.generate(projectId, provider, step, frozen, prior, boundaries, strategy);
                com.novelagent.agent.application.ModelOutputAvailability.requireAvailable(result.text());
                if (result.materials() != null) {
                    store.saveMaterials(projectId, id, result.text(), result.materials());
                } else {
                    store.save(projectId, id, stage, result.text());
                }
                prior += "\n\n【" + stage + "】\n" + result.text();
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
