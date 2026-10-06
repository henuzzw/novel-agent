package com.novelagent.memory.application;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record NovelMemoryContext(List<SemanticMemory> semanticMemories, List<GraphFact> graphFacts,
        MemoryUsage usage, List<SemanticMemory> futureContext, List<String> trimmedSources,
        String boundaryFingerprint) {
    public NovelMemoryContext(List<SemanticMemory> semanticMemories, List<GraphFact> graphFacts, MemoryUsage usage) {
        this(semanticMemories, graphFacts, usage, List.of(), List.of(), fingerprint(""));
    }

    public static final long FUTURE_PLAN = -3;

    public record SemanticMemory(int chapterNumber, long canonVersion, double similarity, String summary,
            String content, boolean recentChapter, String chapterContract, String chapterBody) {
        public SemanticMemory(int chapterNumber, long canonVersion, double similarity, String summary,
                String content) {
            this(chapterNumber, canonVersion, similarity, summary, content, false, null, null);
        }

        public SemanticMemory(int chapterNumber, long canonVersion, double similarity, String summary,
                String content, boolean recentChapter) {
            this(chapterNumber, canonVersion, similarity, summary, content, recentChapter, null, null);
        }

        public boolean futurePlan() { return canonVersion == FUTURE_PLAN; }
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
            result.append("【只读工具：相关历史章节】\n");
            for (SemanticMemory memory : semanticMemories) {
                if (memory.futurePlan()) continue;
                result.append("- 第").append(memory.chapterNumber()).append("章（")
                        .append(memory.canonVersion() > 0 ? "正史 V" + memory.canonVersion()
                                : memory.canonVersion() == 0 ? "作者已确认，未提交正史"
                                : memory.canonVersion() == -1 ? "仅合同已确认" : "前文缺失")
                        .append("）摘要：").append(memory.summary()).append('\n');
                if (memory.content() != null && !memory.content().isBlank()) {
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
                if (fact.evidence() != null && !fact.evidence().isBlank()) {
                    result.append("（依据：").append(fact.evidence()).append('）');
                }
                result.append('\n');
            }
        }
        if (!futureContext.isEmpty()) {
            result.append("【未来规划边界：不得作为已发生正史或人物已知信息】\n");
            for (SemanticMemory future : futureContext) {
                result.append(future.summary()).append('\n');
                if (future.content() != null && !future.content().isBlank()) result.append(future.content()).append('\n');
            }
        }
        if (!trimmedSources.isEmpty()) {
            result.append("【预算裁剪】").append(String.join("；", trimmedSources)).append('\n');
        }
        return result.toString().strip();
    }

    public static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
