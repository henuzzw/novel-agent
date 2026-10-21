package com.novelagent.memory.application;

import static com.novelagent.platform.support.Text.hasText;

import com.novelagent.platform.support.Sha256;
import java.util.List;

public record NovelMemoryContext(List<SemanticMemory> semanticMemories, List<GraphFact> graphFacts,
        MemoryUsage usage, List<SemanticMemory> futureContext, List<String> trimmedSources,
        String boundaryFingerprint) {
    public NovelMemoryContext(List<SemanticMemory> semanticMemories, List<GraphFact> graphFacts, MemoryUsage usage) {
        this(semanticMemories, graphFacts, usage, List.of(), List.of(), Sha256.ofUtf8(""));
    }

    public static final long FUTURE_PLAN = -3;
    public static final long AUTHOR_PLANNING = -4;

    public record SemanticMemory(int chapterNumber, long canonVersion, double similarity, String summary,
            String content, boolean recentChapter, String chapterContract, String chapterBody, String sourceKey) {
        public SemanticMemory(int chapterNumber, long canonVersion, double similarity, String summary,
                String content, boolean recentChapter, String chapterContract, String chapterBody) {
            this(chapterNumber, canonVersion, similarity, summary, content, recentChapter, chapterContract, chapterBody, null);
        }
        public SemanticMemory(int chapterNumber, long canonVersion, double similarity, String summary,
                String content) {
            this(chapterNumber, canonVersion, similarity, summary, content, false, null, null);
        }

        public SemanticMemory(int chapterNumber, long canonVersion, double similarity, String summary,
                String content, boolean recentChapter) {
            this(chapterNumber, canonVersion, similarity, summary, content, recentChapter, null, null);
        }

        public boolean futurePlan() { return canonVersion == FUTURE_PLAN; }
        public boolean authorPlanning() { return canonVersion == AUTHOR_PLANNING; }
        public String retrievalKey() { return sourceKey == null ? chapterNumber + ":" + canonVersion : sourceKey; }
    }

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
        StringBuilder result = new StringBuilder();
        if (semanticMemories.isEmpty() && graphFacts.isEmpty()) result.append("暂无已提交的历史正史。\n");
        if (!semanticMemories.isEmpty()) {
            result.append("【只读工具：相关资料；规划与历史事实分开使用】\n");
            for (SemanticMemory memory : semanticMemories) {
                if (memory.futurePlan()) continue;
                if (memory.authorPlanning()) {
                    result.append("- 作者侧规划（非已发生事实，不代表角色知情，不提前兑现）：")
                            .append(memory.summary()).append('\n');
                    result.append(memory.content()).append('\n');
                    continue;
                }
                result.append("- 第").append(memory.chapterNumber()).append("章（")
                        .append(memory.canonVersion() > 0 ? "正史 V" + memory.canonVersion()
                                : memory.canonVersion() == 0 ? "作者已确认，未提交正史"
                                : memory.canonVersion() == -1 ? "仅合同已确认" : "前文缺失")
                        .append("）摘要：").append(memory.summary()).append('\n');
                if (hasText(memory.content())) {
                    result.append(memory.recentChapter() ? "  前章合同与正文：" : "  相关片段：")
                            .append(memory.content()).append('\n');
                }
            }
        }
        if (!graphFacts.isEmpty()) {
            result.append("【只读工具：相关正史事实】\n");
            for (GraphFact fact : graphFacts) {
                result.append("- ").append(fact.subject()).append(' ')
                        .append(fact.predicate()).append(' ').append(fact.object());
                if (hasText(fact.evidence())) {
                    result.append("（依据：").append(fact.evidence()).append('）');
                }
                result.append('\n');
            }
        }
        if (!futureContext.isEmpty()) {
            result.append("【未来规划边界：不得作为已发生正史或人物已知信息】\n");
            for (SemanticMemory future : futureContext) {
                result.append(future.summary()).append('\n');
                if (hasText(future.content())) result.append(future.content()).append('\n');
            }
        }
        if (!trimmedSources.isEmpty()) {
            result.append("【预算裁剪】").append(String.join("；", trimmedSources)).append('\n');
        }
        return result.toString().strip();
    }
}
