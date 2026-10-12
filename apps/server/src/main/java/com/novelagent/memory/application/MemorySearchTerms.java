package com.novelagent.memory.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact names first, then bounded lexical fallback; no additional model request for query generation. */
public final class MemorySearchTerms {
    private static final Pattern WORDS = Pattern.compile("[a-zA-Z0-9_]{2,40}|[\\p{IsHan}]{2,}");
    private static final Set<String> STOP = Set.of("本章", "章节", "人物", "场景", "目标", "核心", "要求", "故事", "主角", "计划");

    private MemorySearchTerms() { }

    public static List<String> keywords(String query, List<String> explicit) {
        Set<String> terms = new LinkedHashSet<>();
        explicit.stream().filter(value -> value != null && !value.isBlank() && value.length() <= 100)
                .limit(12).forEach(terms::add);
        var matcher = WORDS.matcher(query == null ? "" : query.substring(0, Math.min(2000, query.length())));
        while (matcher.find() && terms.size() < 32) {
            String word = matcher.group();
            if (word.charAt(0) < 128 || word.length() <= 4) terms.add(word);
            else for (int i = 0; i + 2 <= word.length() && terms.size() < 32; i++) {
                String term = word.substring(i, i + 2);
                if (!STOP.contains(term)) terms.add(term);
            }
        }
        terms.removeAll(STOP);
        return List.copyOf(terms);
    }
}
