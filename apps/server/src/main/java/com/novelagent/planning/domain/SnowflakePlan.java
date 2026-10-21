package com.novelagent.planning.domain;

import static com.novelagent.platform.support.Text.isBlank;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.novelagent.agent.application.ModelOutputAvailability;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.SnowflakeStep;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Intermediate creative prose is a candidate, never a published Bible or canonical fact. */
public record SnowflakePlan(UUID id, UUID projectId, String mode, ModelProvider provider,
        String status, String activeStage, String core, String characters, String world,
        String plot, String errorMessage, Instant createdAt, Instant updatedAt, Map<String, String> steps,
        UUID sourceImportId) {

    /** Read-only warning projection; persisted responses are changed only by an explicit resume. */
    @JsonProperty("invalidSteps")
    public Map<String, String> invalidSteps() {
        var invalid = new LinkedHashMap<String, String>();
        boolean contaminated = false;
        for (var stage : SnowflakeStep.values()) {
            String text = steps.get(stage.name());
            if (isBlank(text)) continue;
            if (ModelOutputAvailability.unavailable(text)) contaminated = true;
            if (contaminated) invalid.put(stage.name(), "资料未读取或依赖无效底稿，继续时需重新生成");
        }
        return Map.copyOf(invalid);
    }

    public SnowflakePlan(UUID id, UUID projectId, String mode, ModelProvider provider,
            String status, String activeStage, String core, String characters, String world,
            String plot, String errorMessage, Instant createdAt, Instant updatedAt, Map<String, String> steps) {
        this(id, projectId, mode, provider, status, activeStage, core, characters, world,
                plot, errorMessage, createdAt, updatedAt, steps, null);
    }

    public SnowflakePlan(UUID id, UUID projectId, String mode, ModelProvider provider,
            String status, String activeStage, String core, String characters, String world,
            String plot, String errorMessage, Instant createdAt, Instant updatedAt) {
        this(id, projectId, mode, provider, status, activeStage, core, characters, world,
                plot, errorMessage, createdAt, updatedAt, Map.of());
    }

    public String context() {
        if (!steps.isEmpty()) {
            var result = new StringBuilder();
            for (var step : SnowflakeStep.values()) {
                String text = steps.get(step.name());
                if (text != null) result.append("\n\n【").append(step.label()).append("】\n").append(text);
            }
            return result.toString().strip();
        }
        return "【故事核心与梗概】\n" + core + "\n\n【人物设计】\n" + characters
                + "\n\n【世界构建】\n" + world + "\n\n【三幕情节与悬念节奏】\n" + plot;
    }
}
