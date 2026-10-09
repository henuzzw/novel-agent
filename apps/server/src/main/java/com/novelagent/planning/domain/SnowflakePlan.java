package com.novelagent.planning.domain;

import com.novelagent.planning.application.ModelProvider;
import java.time.Instant;
import java.util.UUID;

/** Intermediate creative prose is a candidate, never a published Bible or canonical fact. */
public record SnowflakePlan(UUID id, UUID projectId, String mode, ModelProvider provider,
        String status, String activeStage, String core, String characters, String world,
        String plot, String errorMessage, Instant createdAt, Instant updatedAt, java.util.Map<String, String> steps) {

    public SnowflakePlan(UUID id, UUID projectId, String mode, ModelProvider provider,
            String status, String activeStage, String core, String characters, String world,
            String plot, String errorMessage, Instant createdAt, Instant updatedAt) {
        this(id, projectId, mode, provider, status, activeStage, core, characters, world,
                plot, errorMessage, createdAt, updatedAt, java.util.Map.of());
    }

    public String context() {
        if (!steps.isEmpty()) {
            var result = new StringBuilder();
            for (var step : com.novelagent.planning.application.SnowflakeStep.values()) {
                String text = steps.get(step.name());
                if (text != null) result.append("\n\n【").append(step.label()).append("】\n").append(text);
            }
            return result.toString().strip();
        }
        return "【故事核心与梗概】\n" + core + "\n\n【人物设计】\n" + characters
                + "\n\n【世界构建】\n" + world + "\n\n【三幕情节与悬念节奏】\n" + plot;
    }
}
