package com.novelagent.planning.domain;

import com.novelagent.planning.application.ModelProvider;
import java.time.Instant;
import java.util.UUID;

/** Intermediate creative prose is a candidate, never a published Bible or canonical fact. */
public record SnowflakePlan(UUID id, UUID projectId, String mode, ModelProvider provider,
        String status, String activeStage, String core, String characters, String world,
        String plot, String errorMessage, Instant createdAt, Instant updatedAt) {

    public String context() {
        return "【故事核心与梗概】\n" + core + "\n\n【人物设计】\n" + characters
                + "\n\n【世界构建】\n" + world + "\n\n【三幕情节与悬念节奏】\n" + plot;
    }
}
