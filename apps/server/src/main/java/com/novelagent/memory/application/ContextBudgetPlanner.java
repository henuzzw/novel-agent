package com.novelagent.memory.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.AgentStage;
import com.novelagent.planning.application.ModelProvider;
import org.springframework.stereotype.Component;

@Component
public class ContextBudgetPlanner {
    private final ObjectMapper mapper;
    private final MemoryBudgetProperties memoryProperties;
    private final ModelContextProperties modelProperties;

    public ContextBudgetPlanner(ObjectMapper mapper, MemoryBudgetProperties memoryProperties,
            ModelContextProperties modelProperties) {
        this.mapper = mapper;
        this.memoryProperties = memoryProperties;
        this.modelProperties = modelProperties;
    }

    public MemoryBudgetPlan plan(AgentStage stage, ModelProvider provider, Object... fixedInputs) {
        if (!stage.usesLongTermMemory()) {
            throw new IllegalArgumentException("该 Agent 阶段不使用长期记忆：" + stage);
        }
        MemoryBudgetProperties.TaskBudget task = memoryProperties.require(stage);
        ModelContextProperties.Capacity model = modelProperties.require(provider);
        int fixedInputTokens = estimateFixedInputs(fixedInputs);
        int available = Math.max(0, model.getContextWindowTokens() - fixedInputTokens
                - task.getReservedOutputTokens() - model.getSafetyMarginTokens());
        int effective = Math.min(task.getDesiredTokens(), available);
        if (effective < task.getMinimumTokens()) {
            throw new IllegalArgumentException("模型剩余上下文不足，无法满足 " + stage
                    + " 的最低长期记忆预算：需要 " + task.getMinimumTokens() + " Token，当前仅 " + effective);
        }
        return new MemoryBudgetPlan(stage, provider, model.getContextWindowTokens(), fixedInputTokens,
                task.getReservedOutputTokens(), model.getSafetyMarginTokens(), task.getDesiredTokens(),
                task.getMinimumTokens(), effective, task.getMaxSemanticMemories(), task.getMaxGraphFacts());
    }

    private int estimateFixedInputs(Object[] fixedInputs) {
        int tokens = 0;
        for (Object input : fixedInputs) {
            if (input != null) {
                tokens += MemoryBudgetAllocator.estimateTokens(serialize(input));
            }
        }
        return tokens;
    }

    private String serialize(Object value) {
        if (value instanceof String text) {
            return text;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法估算 Agent 固定输入 Token", exception);
        }
    }
}
