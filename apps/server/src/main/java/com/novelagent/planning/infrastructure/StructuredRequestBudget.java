package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.agent.application.AgentRunRecorder.ContextBudget;
import com.novelagent.memory.application.MemoryBudgetAllocator;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.planning.application.ModelProvider;

/** Validates the final request, including edited instructions, schema and output/safety reserves. */
final class StructuredRequestBudget {
    private final ModelContextProperties context;

    StructuredRequestBudget(ModelContextProperties context) {
        this.context = context;
    }

    ContextBudget requireCapacity(
            ModelProvider provider,
            String systemPrompt,
            String userPrompt,
            JsonNode schema,
            int reservedOutputTokens) {
        if (reservedOutputTokens < 0) {
            throw new IllegalArgumentException("模型输出预留不能为负数");
        }
        var capacity = context.require(provider);
        int window = capacity.getContextWindowTokens();
        int safety = capacity.getSafetyMarginTokens();
        long input = MemoryBudgetAllocator.estimateTokens(
                (systemPrompt == null ? "" : systemPrompt) + "\n\n"
                        + (userPrompt == null ? "" : userPrompt) + "\n\n" + schema);
        if (input + reservedOutputTokens + safety > window) {
            throw new IllegalArgumentException("最终模型请求超过配置的上下文容量，请减少输入或输出预留");
        }
        return new ContextBudget(window, safety, input, reservedOutputTokens);
    }
}
