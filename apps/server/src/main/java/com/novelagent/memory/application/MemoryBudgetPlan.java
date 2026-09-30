package com.novelagent.memory.application;

import com.novelagent.agent.application.AgentStage;
import com.novelagent.planning.application.ModelProvider;

public record MemoryBudgetPlan(
        AgentStage stage,
        ModelProvider provider,
        int contextWindowTokens,
        int fixedInputTokens,
        int reservedOutputTokens,
        int safetyMarginTokens,
        int desiredMemoryTokens,
        int minimumMemoryTokens,
        int effectiveMemoryTokens,
        int maxSemanticMemories,
        int maxGraphFacts) {
}
