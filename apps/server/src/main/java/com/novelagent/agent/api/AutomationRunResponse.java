package com.novelagent.agent.api;

import com.novelagent.agent.domain.AutomationRun;
import com.novelagent.agent.domain.AutomationStatus;
import com.novelagent.agent.domain.AutomationStep;
import com.novelagent.planning.application.ModelProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AutomationRunResponse(UUID id, UUID projectId, UUID outlineId, int firstChapter,
        int lastChapter, int currentChapter, ModelProvider provider, AutomationStatus status,
        boolean cancelRequested, int attempt, String waitingReason, String errorCode,
        List<AutomationStep> steps, Instant createdAt, Instant updatedAt, boolean qualityReviewEnabled,
        int maxAutoRevisionRounds, int maxGenerationSteps, int usedGenerationSteps, int usedAutoRevisionRounds) {
    public static AutomationRunResponse from(AutomationRun run) {
        return new AutomationRunResponse(run.getId(), run.getProjectId(), run.getOutlineId(),
                run.getFirstChapter(), run.getLastChapter(), run.getCurrentChapter(), run.getProvider(),
                run.getStatus(), run.isCancelRequested(), run.getAttempt(), run.getWaitingReason(),
                run.getErrorCode(), run.getSteps(), run.getCreatedAt(), run.getUpdatedAt(), run.isQualityReviewEnabled(),
                run.getMaxAutoRevisionRounds(), run.getMaxGenerationSteps(), run.getUsedGenerationSteps(), run.getUsedAutoRevisionRounds());
    }
}
