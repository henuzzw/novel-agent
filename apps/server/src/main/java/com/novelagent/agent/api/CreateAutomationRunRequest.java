package com.novelagent.agent.api;

import com.novelagent.planning.application.ModelProvider;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record CreateAutomationRunRequest(@Min(1) @Max(100000) int firstChapter,
        @Min(1) @Max(100000) int lastChapter, ModelProvider provider,
        @Size(max = 2000) String instruction, boolean qualityReviewEnabled,
        @Min(0) @Max(3) int maxAutoRevisionRounds, @Min(1) @Max(500) Integer maxGenerationSteps) {
    public CreateAutomationRunRequest(int firstChapter, int lastChapter, ModelProvider provider, String instruction,
            boolean qualityReviewEnabled) {
        this(firstChapter, lastChapter, provider, instruction, qualityReviewEnabled, 0, null);
    }
    public CreateAutomationRunRequest(int firstChapter, int lastChapter, ModelProvider provider, String instruction) {
        this(firstChapter, lastChapter, provider, instruction, false);
    }

    public int generationStepLimit() { return maxGenerationSteps == null ? 100 : maxGenerationSteps; }
}
