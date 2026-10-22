package com.novelagent.agent.api.dto;

import com.novelagent.planning.application.ModelProvider;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record CreateAutomationRunRequest(@Min(1) @Max(100000) int firstChapter,
        @Min(1) @Max(100000) int lastChapter, ModelProvider provider,
        @Size(max = 2000) String instruction, boolean qualityReviewEnabled,
        @Min(0) @Max(3) int maxAutoRevisionRounds, @Min(1) @Max(500) Integer maxGenerationSteps,
        @Min(1) @Max(10) Integer maxLoopRounds, Boolean autoPublish, Boolean autoMemory) {
    public CreateAutomationRunRequest(int firstChapter, int lastChapter, ModelProvider provider, String instruction,
            boolean qualityReviewEnabled, int maxAutoRevisionRounds, Integer maxGenerationSteps) {
        this(firstChapter, lastChapter, provider, instruction, qualityReviewEnabled, maxAutoRevisionRounds, maxGenerationSteps, null, null, null);
    }
    public CreateAutomationRunRequest(int firstChapter, int lastChapter, ModelProvider provider, String instruction,
            boolean qualityReviewEnabled) {
        this(firstChapter, lastChapter, provider, instruction, qualityReviewEnabled, 0, null);
    }
    public CreateAutomationRunRequest(int firstChapter, int lastChapter, ModelProvider provider, String instruction) {
        this(firstChapter, lastChapter, provider, instruction, false);
    }

    public int generationStepLimit() { return maxGenerationSteps == null ? 100 : maxGenerationSteps; }
    public int loopRoundLimit() { return maxLoopRounds == null ? 3 : maxLoopRounds; }
    public boolean automaticPublication() { return Boolean.TRUE.equals(autoPublish); }
    public boolean automaticMemory() { return autoMemory == null || autoMemory; }
}
