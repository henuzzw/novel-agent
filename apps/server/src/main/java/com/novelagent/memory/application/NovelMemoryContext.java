package com.novelagent.memory.application;

import java.util.List;

public record NovelMemoryContext(List<SemanticMemory> semanticMemories, List<GraphFact> graphFacts,
        MemoryUsage usage) {
    public record SemanticMemory(int chapterNumber, long canonVersion, double similarity, String summary,
            String content) {}

    public record GraphFact(long canonVersion, String subject, String predicate, String object, String evidence) {}

    public record MemoryUsage(
            String stage,
            String provider,
            int contextWindowTokens,
            int fixedInputTokens,
            int reservedOutputTokens,
            int safetyMarginTokens,
            int desiredMemoryTokens,
            int minimumMemoryTokens,
            int budgetTokens,
            int estimatedTokens,
            boolean truncated,
            List<String> toolsUsed) {
    }

    public String toPromptText() {
        if (semanticMemories.isEmpty() && graphFacts.isEmpty()) return "暂无已提交的历史正史。";
        StringBuilder result = new StringBuilder();
        if (!semanticMemories.isEmpty()) {
            result.append("【只读工具：相关历史章节】\n");
            for (SemanticMemory memory : semanticMemories) {
                result.append("- 第").append(memory.chapterNumber()).append("章（正史 V")
                        .append(memory.canonVersion()).append("）摘要：").append(memory.summary()).append('\n');
                if (memory.content() != null && !memory.content().isBlank()) {
                    result.append("  相关片段：").append(memory.content()).append('\n');
                }
            }
        }
        if (!graphFacts.isEmpty()) {
            result.append("【只读工具：相关正史事实】\n");
            for (GraphFact fact : graphFacts) {
                result.append("- ").append(fact.subject()).append(' ')
                        .append(fact.predicate()).append(' ').append(fact.object());
                if (fact.evidence() != null && !fact.evidence().isBlank()) {
                    result.append("（依据：").append(fact.evidence()).append('）');
                }
                result.append('\n');
            }
        }
        return result.toString().strip();
    }

}
