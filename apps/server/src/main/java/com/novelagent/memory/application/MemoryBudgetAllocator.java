package com.novelagent.memory.application;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MemoryBudgetAllocator {
    public NovelMemoryContext allocate(List<NovelMemoryContext.SemanticMemory> semanticCandidates,
            List<NovelMemoryContext.GraphFact> factCandidates, MemoryBudgetPlan plan, List<String> toolsUsed) {
        int budgetTokens = plan.effectiveMemoryTokens();
        int maxSemanticMemories = plan.maxSemanticMemories();
        int maxGraphFacts = plan.maxGraphFacts();
        int remaining = budgetTokens;
        boolean truncated = semanticCandidates.size() > maxSemanticMemories || factCandidates.size() > maxGraphFacts;
        List<NovelMemoryContext.SemanticMemory> semantic = new ArrayList<>();
        for (NovelMemoryContext.SemanticMemory candidate : semanticCandidates.stream().limit(maxSemanticMemories).toList()) {
            String summary = trimToTokens(candidate.summary(), Math.min(500, remaining));
            remaining -= estimateTokens(summary);
            String excerpt = trimToTokens(candidate.content(), Math.min(500, remaining));
            remaining -= estimateTokens(excerpt);
            if (summary.isBlank() && excerpt.isBlank()) break;
            truncated |= !summary.equals(nullToEmpty(candidate.summary())) || !excerpt.equals(nullToEmpty(candidate.content()));
            semantic.add(new NovelMemoryContext.SemanticMemory(candidate.chapterNumber(), candidate.canonVersion(),
                    candidate.similarity(), summary, excerpt));
            if (remaining <= 0) break;
        }

        List<NovelMemoryContext.GraphFact> facts = new ArrayList<>();
        if (remaining > 0) {
            for (NovelMemoryContext.GraphFact candidate : factCandidates.stream().limit(maxGraphFacts).toList()) {
                String factText = candidate.subject() + candidate.predicate() + candidate.object();
                int factTokens = estimateTokens(factText);
                if (factTokens > remaining) { truncated = true; break; }
                int evidenceBudget = Math.min(120, Math.max(0, remaining - factTokens));
                String evidence = trimToTokens(candidate.evidence(), evidenceBudget);
                remaining -= factTokens + estimateTokens(evidence);
                truncated |= !evidence.equals(nullToEmpty(candidate.evidence()));
                facts.add(new NovelMemoryContext.GraphFact(candidate.canonVersion(), candidate.subject(),
                        candidate.predicate(), candidate.object(), evidence));
                if (remaining <= 0) break;
            }
        } else if (!factCandidates.isEmpty()) {
            truncated = true;
        }
        NovelMemoryContext draft = context(semantic, facts, 0, truncated, plan, toolsUsed);
        while (estimateTokens(draft.toPromptText()) > budgetTokens && !facts.isEmpty()) {
            facts.removeLast();
            truncated = true;
            draft = context(semantic, facts, 0, truncated, plan, toolsUsed);
        }
        while (estimateTokens(draft.toPromptText()) > budgetTokens && !semantic.isEmpty()) {
            var last = semantic.getLast();
            if (last.content() != null && !last.content().isBlank()) {
                semantic.set(semantic.size() - 1, new NovelMemoryContext.SemanticMemory(last.chapterNumber(),
                        last.canonVersion(), last.similarity(), last.summary(), ""));
            } else {
                semantic.removeLast();
            }
            truncated = true;
            draft = context(semantic, facts, 0, truncated, plan, toolsUsed);
        }
        int used = estimateTokens(draft.toPromptText());
        return new NovelMemoryContext(List.copyOf(semantic), List.copyOf(facts),
                usage(plan, used, truncated, toolsUsed));
    }

    private NovelMemoryContext context(List<NovelMemoryContext.SemanticMemory> semantic,
            List<NovelMemoryContext.GraphFact> facts, int used, boolean truncated,
            MemoryBudgetPlan plan, List<String> toolsUsed) {
        return new NovelMemoryContext(List.copyOf(semantic), List.copyOf(facts),
                usage(plan, used, truncated, toolsUsed));
    }

    private NovelMemoryContext.MemoryUsage usage(MemoryBudgetPlan plan, int used, boolean truncated,
            List<String> toolsUsed) {
        return new NovelMemoryContext.MemoryUsage(
                plan.stage().name(),
                plan.provider().name(),
                plan.contextWindowTokens(),
                plan.fixedInputTokens(),
                plan.reservedOutputTokens(),
                plan.safetyMarginTokens(),
                plan.desiredMemoryTokens(),
                plan.minimumMemoryTokens(),
                plan.effectiveMemoryTokens(),
                used,
                truncated,
                List.copyOf(toolsUsed));
    }

    public static int estimateTokens(String value) {
        if (value == null || value.isBlank()) return 0;
        int tokens = 0;
        int asciiRun = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint <= 0x7f) asciiRun++;
            else { tokens += 1 + (asciiRun + 3) / 4; asciiRun = 0; }
        }
        return tokens + (asciiRun + 3) / 4;
    }

    static String trimToTokens(String value, int maxTokens) {
        String normalized = nullToEmpty(value).strip();
        if (maxTokens <= 0 || normalized.isEmpty()) return "";
        if (estimateTokens(normalized) <= maxTokens) return normalized;
        int low = 0;
        int high = normalized.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (estimateTokens(normalized.substring(0, middle)) <= Math.max(1, maxTokens - 1)) low = middle;
            else high = middle - 1;
        }
        return normalized.substring(0, low).stripTrailing() + "…";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
