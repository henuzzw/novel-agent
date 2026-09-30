package com.novelagent.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.AgentStage;
import com.novelagent.planning.application.ModelProvider;
import org.junit.jupiter.api.Test;

class ContextBudgetPlannerTest {
    private final MemoryBudgetProperties memory = new MemoryBudgetProperties();
    private final ModelContextProperties models = new ModelContextProperties();
    private final ContextBudgetPlanner planner = new ContextBudgetPlanner(new ObjectMapper(), memory, models);

    @Test
    void usesStageSpecificBudgetWhenModelHasEnoughCapacity() {
        MemoryBudgetPlan manuscript = planner.plan(
                AgentStage.MANUSCRIPT, ModelProvider.DEEPSEEK, "固定上下文".repeat(100));
        MemoryBudgetPlan contract = planner.plan(
                AgentStage.CHAPTER_CONTRACT, ModelProvider.DEEPSEEK, "固定上下文".repeat(100));

        assertThat(manuscript.effectiveMemoryTokens()).isEqualTo(8000);
        assertThat(contract.effectiveMemoryTokens()).isEqualTo(5000);
    }

    @Test
    void shrinksBudgetToAvailableModelContext() {
        models.getModels().put(ModelProvider.DEEPSEEK, new ModelContextProperties.Capacity(12000, 1000));
        memory.getBudgets().put(AgentStage.CHAPTER_CONTRACT,
                new MemoryBudgetProperties.TaskBudget(5000, 1000, 3000, 5, 12));

        MemoryBudgetPlan plan = planner.plan(
                AgentStage.CHAPTER_CONTRACT, ModelProvider.DEEPSEEK, "中文".repeat(2000));

        assertThat(plan.effectiveMemoryTokens()).isLessThan(5000);
        assertThat(plan.effectiveMemoryTokens()).isGreaterThanOrEqualTo(1000);
    }

    @Test
    void rejectsWhenMinimumMemoryCannotFit() {
        models.getModels().put(ModelProvider.DEEPSEEK, new ModelContextProperties.Capacity(6000, 1000));

        assertThatThrownBy(() -> planner.plan(
                AgentStage.MANUSCRIPT, ModelProvider.DEEPSEEK, "中文".repeat(1000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最低长期记忆预算");
    }
}
