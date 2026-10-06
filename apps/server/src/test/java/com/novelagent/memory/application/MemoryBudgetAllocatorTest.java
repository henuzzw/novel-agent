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

    @Test
    void prioritizesRecentContractsAndManuscriptsWithinMemoryBudget() {
        String previous = "合同：人物目标和阻力。正文：对话推进场景。".repeat(120);
        var context = new MemoryBudgetAllocator().allocate(List.of(
                new NovelMemoryContext.SemanticMemory(3, 5, 1.0, "第三章摘要", previous, true),
                new NovelMemoryContext.SemanticMemory(2, 0, 1.0, "第二章摘要", previous, true)),
                List.of(), plan(900), List.of("GET_RECENT_CHAPTER_SUMMARIES"));

        assertThat(context.semanticMemories()).hasSize(2);
        assertThat(context.toPromptText()).contains("第三章摘要", "第二章摘要", "前章合同与正文", "作者已确认，未提交正史");
        assertThat(context.usage().estimatedTokens()).isLessThanOrEqualTo(900);
        assertThat(context.usage().truncated()).isTrue();
    }

    @Test
    void manuscriptKeepsLongPreviousBodyBeforeCompactContractAndReservesFacts() {
        String body = "前章的行动和对话。\n\n".repeat(650);
        String contract = "前章合同约束。".repeat(180);
        var memories = List.of(
                recent(3, body, contract),
                recent(2, "更早章节正文。\n\n".repeat(300), contract));
        var context = new MemoryBudgetAllocator().allocate(memories,
                List.of(new NovelMemoryContext.GraphFact(5, "主角", "得知", "纸条属于同桌", "第三章结尾")),
                plan(8000), List.of("GET_RECENT_CHAPTER_SUMMARIES"));

        String previous = context.semanticMemories().getFirst().content();
        assertThat(previous).startsWith("前章正文：");
        assertThat(previous.indexOf("前章合同：")).isGreaterThan(4000);
        assertThat(previous).contains("前章合同：");
        assertThat(context.semanticMemories()).hasSize(2);
        assertThat(context.semanticMemories().get(1).content()).startsWith("前章正文：");
        assertThat(context.graphFacts()).hasSize(1);
        assertThat(context.usage().estimatedTokens()).isLessThanOrEqualTo(8000);
    }

    @Test
    void smallerContextShrinksRecentBodiesAndContractStageUsesLessProse() {
        var memories = List.of(recent(3, "长正文。\n\n".repeat(1800), "长合同。".repeat(300)),
                recent(2, "更早正文。\n\n".repeat(800), "长合同。".repeat(300)));
        var manuscript = new MemoryBudgetAllocator().allocate(memories, List.of(), plan(4000), List.of());
        var contract = new MemoryBudgetAllocator().allocate(memories, List.of(),
                new MemoryBudgetPlan(AgentStage.CHAPTER_CONTRACT, ModelProvider.LOCAL_CODEX,
                        128000, 1000, 3000, 4000, 5000, 40, 5000, 3, 10), List.of());

        assertThat(manuscript.semanticMemories().getFirst().content().length())
                .isLessThan(contract.semanticMemories().getFirst().content().length() * 3);
        assertThat(manuscript.semanticMemories().getFirst().content().length())
                .isGreaterThan(contract.semanticMemories().getFirst().content().length());
        assertThat(manuscript.usage().estimatedTokens()).isLessThanOrEqualTo(4000);
        assertThat(contract.usage().estimatedTokens()).isLessThanOrEqualTo(5000);
    }

    @Test
    void keepsEndingAtParagraphBoundaryAndDeduplicatesOtherRetrievalOfSameChapter() {
        String body = "开头已经离开。\n\n" + "中段行动和对话。\n\n".repeat(1500) + "最后他停在门口，没有进去。";
        var context = new MemoryBudgetAllocator().allocate(List.of(
                recent(3, body, "合同约束"),
                new NovelMemoryContext.SemanticMemory(3, 5, 0.9, "重复摘要", body),
                recent(2, "前前章", "合同")), List.of(), plan(8000), List.of());
        assertThat(context.semanticMemories()).hasSize(2);
        assertThat(context.semanticMemories().getFirst().chapterBody()).endsWith("最后他停在门口，没有进去。")
                .doesNotContain("开头已经离开").startsWith("中段行动和对话。");
        assertThat(context.toPromptText()).contains("正文已裁剪，仅保留完整尾段");
        assertThat(context.usage().estimatedTokens()).isEqualTo(MemoryBudgetAllocator.estimateTokens(context.toPromptText()))
                .isLessThanOrEqualTo(8000);
    }

    @Test
    void shortBodyAppearsOnceAndUnfittableSingleParagraphIsExplicitlyMissing() {
        var shortContext = new MemoryBudgetAllocator().allocate(List.of(recent(1, "唯一短正文。", "合同")),
                List.of(), plan(1000), List.of());
        assertThat(shortContext.semanticMemories().getFirst().chapterBody()).isEqualTo("唯一短正文。");
        assertThat(shortContext.toPromptText().split("唯一短正文。", -1)).hasSize(2);
        var huge = new MemoryBudgetAllocator().allocate(List.of(recent(1, "整段过长".repeat(5000), "合同")),
                List.of(), plan(1000), List.of());
        assertThat(huge.toPromptText()).contains("预算内无完整段落");
        assertThat(huge.semanticMemories().getFirst().chapterBody()).isEmpty();
    }

    @Test
    void futurePlansAreSeparateAndTheirChangesInvalidateFingerprintEvenWhenTrimmed() {
        var future = new NovelMemoryContext.SemanticMemory(4, NovelMemoryContext.FUTURE_PLAN, 1,
                "来源项目=p；大纲=o；大纲行版本=2；圣经=b\n下一章第4章",
                "计划。".repeat(3000) + "未来秘密");
        var context = new MemoryBudgetAllocator().allocate(List.of(recent(3, "前章结尾", "合同"), future),
                List.of(new NovelMemoryContext.GraphFact(1, "主角", "知道", "过去", "证据")), plan(4000), List.of());
        assertThat(context.futureContext()).hasSize(1);
        assertThat(context.semanticMemories()).noneMatch(NovelMemoryContext.SemanticMemory::futurePlan);
        assertThat(context.graphFacts()).noneMatch(fact -> fact.object().contains("未来秘密"));
        assertThat(context.toPromptText()).contains("未来规划边界", "不得作为已发生正史或人物已知信息", "下一章计划已裁剪");
        assertThat(context.usage().estimatedTokens()).isLessThanOrEqualTo(4000);
        var changed = new NovelMemoryContext.SemanticMemory(4, NovelMemoryContext.FUTURE_PLAN, 1,
                future.summary(), future.content() + "计划改变");
        assertThat(new MemoryBudgetAllocator().allocate(List.of(recent(3, "前章结尾", "合同"), changed),
                context.graphFacts(), plan(4000), List.of()).boundaryFingerprint()).isNotEqualTo(context.boundaryFingerprint());
    }

    private NovelMemoryContext.SemanticMemory recent(int chapter, String body, String contract) {
        return new NovelMemoryContext.SemanticMemory(chapter, 5, 1.0, "章节摘要", "前章合同：" + contract
                + "\n前章正文：" + body, true, contract, body);
    }

    private MemoryBudgetPlan plan(int tokens) {
        return new MemoryBudgetPlan(AgentStage.MANUSCRIPT, ModelProvider.LOCAL_CODEX,
                128000, 1000, 10000, 4000, tokens, 40, tokens, 3, 10);
    }
}
