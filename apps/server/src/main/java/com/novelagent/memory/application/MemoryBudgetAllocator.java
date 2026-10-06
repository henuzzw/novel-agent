package com.novelagent.memory.application;

import com.novelagent.agent.application.AgentStage;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

@Component
public class MemoryBudgetAllocator {
    public NovelMemoryContext allocate(List<NovelMemoryContext.SemanticMemory> semanticCandidates,
            List<NovelMemoryContext.GraphFact> factCandidates, MemoryBudgetPlan plan, List<String> toolsUsed) {
        return allocate(semanticCandidates, factCandidates, plan, toolsUsed, List.of(), null, false);
    }

    public static NovelMemoryContext withFutureContext(NovelMemoryContext recalled,
            NovelMemoryContext.SemanticMemory future, MemoryBudgetPlan plan) {
        List<NovelMemoryContext.SemanticMemory> candidates = new ArrayList<>(recalled.semanticMemories());
        candidates.add(future);
        String fingerprint = NovelMemoryContext.fingerprint(recalled.boundaryFingerprint() + "\n"
                + future.summary() + "\n" + future.content());
        return new MemoryBudgetAllocator().allocate(candidates, recalled.graphFacts(), plan,
                recalled.usage() == null ? List.of() : recalled.usage().toolsUsed(), recalled.trimmedSources(), fingerprint,
                recalled.usage() != null && recalled.usage().truncated());
    }

    private NovelMemoryContext allocate(List<NovelMemoryContext.SemanticMemory> semanticCandidates,
            List<NovelMemoryContext.GraphFact> factCandidates, MemoryBudgetPlan plan, List<String> toolsUsed,
            List<String> previousTrims, String inputFingerprint, boolean inputTruncated) {
        int budgetTokens = plan.effectiveMemoryTokens();
        int maxSemanticMemories = plan.maxSemanticMemories();
        int maxGraphFacts = plan.maxGraphFacts();
        String boundaryFingerprint = inputFingerprint != null ? inputFingerprint : NovelMemoryContext.fingerprint(semanticCandidates.stream()
                .filter(candidate -> candidate.recentChapter() || candidate.futurePlan())
                .map(candidate -> candidate.chapterNumber() + ":" + candidate.canonVersion() + "\n"
                        + candidate.summary() + "\n" + candidate.content()).reduce("", (a, b) -> a + "\n" + b));
        List<String> trimmedSources = new ArrayList<>(previousTrims);
        List<NovelMemoryContext.SemanticMemory> future = new ArrayList<>();
        for (var candidate : semanticCandidates.stream().filter(NovelMemoryContext.SemanticMemory::futurePlan).toList()) {
            int limit = Math.min(900, budgetTokens / 5);
            String content = trimToTokens(candidate.content(), Math.max(0, limit - estimateTokens(candidate.summary()) - 60));
            future.add(new NovelMemoryContext.SemanticMemory(candidate.chapterNumber(), candidate.canonVersion(),
                    candidate.similarity(), candidate.summary(), content));
            if (!content.equals(nullToEmpty(candidate.content()).strip())) trimmedSources.add("下一章计划已裁剪");
        }
        LinkedHashMap<Integer, NovelMemoryContext.SemanticMemory> recent = new LinkedHashMap<>();
        semanticCandidates.stream().filter(candidate -> candidate.recentChapter() && !candidate.futurePlan())
                .sorted(Comparator.comparingInt(NovelMemoryContext.SemanticMemory::chapterNumber).reversed())
                .forEach(candidate -> recent.putIfAbsent(candidate.chapterNumber(), candidate));
        List<NovelMemoryContext.SemanticMemory> candidates = new ArrayList<>(recent.values());
        semanticCandidates.stream().filter(candidate -> !candidate.recentChapter() && !candidate.futurePlan())
                .filter(candidate -> !recent.containsKey(candidate.chapterNumber())).forEach(candidates::add);
        int factReserve = factCandidates.isEmpty() ? 0 : Math.min(800, budgetTokens / 10);
        int remaining = Math.max(0, budgetTokens - factReserve
                - estimateTokens(new NovelMemoryContext(List.of(), List.of(), null, future, List.of(), "").toPromptText()));
        boolean truncated = inputTruncated || candidates.size() > maxSemanticMemories || factCandidates.size() > maxGraphFacts
                || !trimmedSources.isEmpty();
        List<NovelMemoryContext.SemanticMemory> semantic = new ArrayList<>();
        int recentIndex = 0;
        for (NovelMemoryContext.SemanticMemory candidate : candidates.stream().limit(maxSemanticMemories).toList()) {
            int itemLimit = candidate.recentChapter() ? recentLimit(plan.stage(), budgetTokens, recentIndex++) : 500;
            int allowance = Math.min(itemLimit, remaining);
            String summary = trimSummary(candidate.summary(), Math.min(candidate.recentChapter() ? 350 : 500, allowance));
            if (estimateTokens(summary) + 20 > remaining) { truncated = true; continue; }
            int contentBudget = Math.max(0, allowance - estimateTokens(summary) - 80);
            String excerpt;
            String body = null;
            String contract = null;
            if (candidate.recentChapter() && candidate.chapterContract() != null) {
                int first = recentIndex == 1 ? 0 : 1;
                int contractCap = plan.stage() == AgentStage.MANUSCRIPT ? (first == 0 ? 320 : 220) : 400;
                int bodyCap = plan.stage() == AgentStage.MANUSCRIPT ? (first == 0 ? 5000 : 1500)
                        : (first == 0 ? 1300 : 700);
                body = trimParagraphTail(candidate.chapterBody(), Math.min(bodyCap, contentBudget));
                contract = trimToTokens(candidate.chapterContract(),
                        Math.min(contractCap, Math.max(0, contentBudget - estimateTokens(body) - 25)));
                excerpt = (body.isBlank() ? "" : "前章正文：" + body + "\n")
                        + (contract.isBlank() ? "" : "前章合同：" + contract);
                truncated |= !contract.equals(nullToEmpty(candidate.chapterContract()).strip())
                        || !body.equals(nullToEmpty(candidate.chapterBody()).strip());
                if (!body.equals(nullToEmpty(candidate.chapterBody()).strip())) {
                    trimmedSources.add("第" + candidate.chapterNumber() + "章正文已裁剪，仅保留完整尾段"
                            + (body.isBlank() ? "（预算内无完整段落）" : ""));
                }
                if (!contract.equals(nullToEmpty(candidate.chapterContract()).strip())) {
                    trimmedSources.add("第" + candidate.chapterNumber() + "章合同已裁剪");
                }
            } else {
                excerpt = trimToTokens(candidate.content(), contentBudget);
                truncated |= !excerpt.equals(nullToEmpty(candidate.content()).strip());
            }
            if (summary.isBlank() && excerpt.isBlank()) break;
            remaining -= estimateTokens(summary) + estimateTokens(excerpt) + 50;
            truncated |= !summary.equals(nullToEmpty(candidate.summary()).strip());
            semantic.add(new NovelMemoryContext.SemanticMemory(candidate.chapterNumber(), candidate.canonVersion(),
                    candidate.similarity(), summary, excerpt, candidate.recentChapter(), contract, body));
            if (remaining <= 0) break;
        }

        remaining += factReserve;
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
        if (semantic.size() < candidates.size()) trimmedSources.add("部分历史资料未纳入预算");
        if (facts.size() < factCandidates.size()) trimmedSources.add("部分正史事实未纳入预算");
        truncated |= !trimmedSources.isEmpty();
        NovelMemoryContext draft = context(semantic, facts, future, trimmedSources, boundaryFingerprint, plan, toolsUsed);
        while (estimateTokens(draft.toPromptText()) > budgetTokens && !facts.isEmpty()) {
            facts.removeLast();
            if (!trimmedSources.contains("部分正史事实未纳入预算")) trimmedSources.add("部分正史事实未纳入预算");
            truncated = true;
            draft = context(semantic, facts, future, trimmedSources, boundaryFingerprint, plan, toolsUsed);
        }
        while (estimateTokens(draft.toPromptText()) > budgetTokens && !semantic.isEmpty()) {
            var last = semantic.getLast();
            semantic.removeLast();
            String notice = "第" + last.chapterNumber() + "章资料未纳入预算";
            if (!trimmedSources.contains(notice)) trimmedSources.add(notice);
            truncated = true;
            draft = context(semantic, facts, future, trimmedSources, boundaryFingerprint, plan, toolsUsed);
        }
        while (estimateTokens(draft.toPromptText()) > budgetTokens && !future.isEmpty()) {
            future.removeLast();
            if (!trimmedSources.contains("下一章计划未纳入预算")) trimmedSources.add("下一章计划未纳入预算");
            truncated = true;
            draft = context(semantic, facts, future, trimmedSources, boundaryFingerprint, plan, toolsUsed);
        }
        while (estimateTokens(draft.toPromptText()) > budgetTokens && !trimmedSources.isEmpty()) {
            trimmedSources.removeLast();
            draft = context(semantic, facts, future, trimmedSources, boundaryFingerprint, plan, toolsUsed);
        }
        int used = estimateTokens(draft.toPromptText());
        return new NovelMemoryContext(List.copyOf(semantic), List.copyOf(facts),
                usage(plan, used, truncated, toolsUsed), List.copyOf(future), List.copyOf(trimmedSources), boundaryFingerprint);
    }

    private static int recentLimit(AgentStage stage, int budgetTokens, int index) {
        if (stage == AgentStage.MANUSCRIPT) {
            return index == 0 ? Math.min(5400, budgetTokens * 65 / 100)
                    : Math.min(1500, budgetTokens * 18 / 100);
        }
        return index == 0 ? Math.min(1800, budgetTokens * 30 / 100)
                : Math.min(900, budgetTokens * 18 / 100);
    }

    private NovelMemoryContext context(List<NovelMemoryContext.SemanticMemory> semantic,
            List<NovelMemoryContext.GraphFact> facts, List<NovelMemoryContext.SemanticMemory> future,
            List<String> trimmedSources, String boundaryFingerprint,
            MemoryBudgetPlan plan, List<String> toolsUsed) {
        return new NovelMemoryContext(List.copyOf(semantic), List.copyOf(facts),
                usage(plan, 0, true, toolsUsed), List.copyOf(future), List.copyOf(trimmedSources), boundaryFingerprint);
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

    private static String trimSummary(String value, int maxTokens) {
        String normalized = nullToEmpty(value).strip();
        if (!normalized.startsWith("来源项目=")) return trimToTokens(normalized, maxTokens);
        int newline = normalized.indexOf('\n');
        if (newline < 0) return normalized;
        String provenance = normalized.substring(0, newline);
        return provenance + "\n" + trimToTokens(normalized.substring(newline + 1),
                Math.max(0, maxTokens - estimateTokens(provenance) - 1));
    }

    static String trimParagraphTail(String value, int maxTokens) {
        String normalized = nullToEmpty(value).strip();
        if (maxTokens <= 0 || normalized.isEmpty()) return "";
        if (estimateTokens(normalized) <= maxTokens) return normalized;
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        var separators = java.util.regex.Pattern.compile("\\R[\\t ]*(?:\\R[\\t ]*)*").matcher(normalized);
        while (separators.find()) starts.add(separators.end());
        String tail = "";
        for (int i = starts.size() - 1; i >= 0; i--) {
            String candidate = normalized.substring(starts.get(i));
            if (estimateTokens(candidate) > maxTokens) break;
            tail = candidate;
        }
        return tail;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
