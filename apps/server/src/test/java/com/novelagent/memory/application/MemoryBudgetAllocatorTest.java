package com.novelagent.memory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.novelagent.agent.application.AgentStage;
import com.novelagent.planning.application.ModelProvider;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryBudgetAllocatorTest {
    @Test
    void keepsPromptWithinBudgetAndPrioritizesSummary() {
        MemoryBudgetAllocator allocator = new MemoryBudgetAllocator();
        var context = allocator.allocate(List.of(new NovelMemoryContext.SemanticMemory(
                1, 1, 0.9, "主角确认旧钥匙来自实验室。", "很长的正文片段。".repeat(80))),
                List.of(new NovelMemoryContext.GraphFact(1, "主角", "持有", "旧钥匙", "第一章结尾取得。")),
                plan(80), List.of("SEARCH_STORY_MEMORY"));

        assertThat(context.usage().estimatedTokens()).isLessThanOrEqualTo(80);
        assertThat(context.usage().truncated()).isTrue();
        assertThat(context.semanticMemories().getFirst().summary()).contains("旧钥匙");
    }

    @Test
    void estimatesChineseMoreConservativelyThanAscii() {
        assertThat(MemoryBudgetAllocator.estimateTokens("中文测试"))
                .isGreaterThan(MemoryBudgetAllocator.estimateTokens("test"));
    }

    private MemoryBudgetPlan plan(int tokens) {
        return new MemoryBudgetPlan(AgentStage.MANUSCRIPT, ModelProvider.LOCAL_CODEX,
                128000, 1000, 10000, 4000, tokens, 40, tokens, 3, 10);
    }
}
