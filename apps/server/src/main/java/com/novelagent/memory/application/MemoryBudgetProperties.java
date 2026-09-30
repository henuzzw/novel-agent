package com.novelagent.memory.application;

import com.novelagent.agent.application.AgentStage;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.memory")
public class MemoryBudgetProperties {
    private Map<AgentStage, TaskBudget> budgets = defaults();

    public Map<AgentStage, TaskBudget> getBudgets() {
        return budgets;
    }

    public void setBudgets(Map<AgentStage, TaskBudget> budgets) {
        this.budgets = budgets;
    }

    public TaskBudget require(AgentStage stage) {
        TaskBudget budget = budgets.get(stage);
        if (budget == null) {
            throw new IllegalStateException("未配置 Agent 阶段的记忆预算：" + stage);
        }
        budget.validate(stage);
        return budget;
    }

    private static Map<AgentStage, TaskBudget> defaults() {
        Map<AgentStage, TaskBudget> values = new EnumMap<>(AgentStage.class);
        values.put(AgentStage.CHAPTER_CONTRACT, new TaskBudget(5000, 2500, 3000, 5, 12));
        values.put(AgentStage.MANUSCRIPT, new TaskBudget(8000, 4000, 10000, 8, 20));
        values.put(AgentStage.CHAPTER_REVIEW, new TaskBudget(6000, 3000, 5000, 6, 16));
        return values;
    }

    public static class TaskBudget {
        private int desiredTokens;
        private int minimumTokens;
        private int reservedOutputTokens;
        private int maxSemanticMemories;
        private int maxGraphFacts;

        public TaskBudget() {
        }

        public TaskBudget(int desiredTokens, int minimumTokens, int reservedOutputTokens,
                int maxSemanticMemories, int maxGraphFacts) {
            this.desiredTokens = desiredTokens;
            this.minimumTokens = minimumTokens;
            this.reservedOutputTokens = reservedOutputTokens;
            this.maxSemanticMemories = maxSemanticMemories;
            this.maxGraphFacts = maxGraphFacts;
        }

        public int getDesiredTokens() {
            return desiredTokens;
        }

        public void setDesiredTokens(int desiredTokens) {
            this.desiredTokens = desiredTokens;
        }

        public int getMinimumTokens() {
            return minimumTokens;
        }

        public void setMinimumTokens(int minimumTokens) {
            this.minimumTokens = minimumTokens;
        }

        public int getReservedOutputTokens() {
            return reservedOutputTokens;
        }

        public void setReservedOutputTokens(int reservedOutputTokens) {
            this.reservedOutputTokens = reservedOutputTokens;
        }

        public int getMaxSemanticMemories() {
            return maxSemanticMemories;
        }

        public void setMaxSemanticMemories(int maxSemanticMemories) {
            this.maxSemanticMemories = maxSemanticMemories;
        }

        public int getMaxGraphFacts() {
            return maxGraphFacts;
        }

        public void setMaxGraphFacts(int maxGraphFacts) {
            this.maxGraphFacts = maxGraphFacts;
        }

        private void validate(AgentStage stage) {
            if (minimumTokens <= 0 || desiredTokens < minimumTokens || reservedOutputTokens <= 0
                    || maxSemanticMemories <= 0 || maxGraphFacts <= 0) {
                throw new IllegalStateException("Agent 阶段的记忆预算配置无效：" + stage);
            }
        }
    }
}
