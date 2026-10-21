package com.novelagent.memory.application;

import static com.novelagent.platform.support.Text.isBlank;
import static java.util.Objects.requireNonNullElse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Text sizing and clipping share the same conservative code point estimate. */
final class MemoryTextBudget {
    private static final Pattern PARAGRAPH_SEPARATOR = Pattern.compile("\\R[\\t ]*(?:\\R[\\t ]*)*");

    private MemoryTextBudget() {
    }

    static int estimateTokens(String value) {
        if (isBlank(value)) {
            return 0;
        }
        int tokens = 0;
        int asciiRun = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint <= 0x7f) {
                if (asciiRun++ % 4 == 0) {
                    tokens++;
                }
            } else {
                tokens++;
                asciiRun = 0;
            }
        }
        return tokens;
    }

    static String trimToTokens(String value, int maxTokens) {
        String normalized = requireNonNullElse(value, "").strip();
        if (maxTokens <= 0 || normalized.isEmpty()) {
            return "";
        }
        if (estimateTokens(normalized) <= maxTokens) {
            return normalized;
        }
        int tokens = 0;
        int asciiRun = 0;
        int end = 0;
        // Reserve one token for the ellipsis and never split a UTF-16 surrogate pair.
        while (end < normalized.length()) {
            int codePoint = normalized.codePointAt(end);
            int cost = codePoint > 0x7f || asciiRun % 4 == 0 ? 1 : 0;
            if (tokens + cost > maxTokens - 1) {
                break;
            }
            tokens += cost;
            asciiRun = codePoint <= 0x7f ? asciiRun + 1 : 0;
            end += Character.charCount(codePoint);
        }
        return normalized.substring(0, end).stripTrailing() + "…";
    }

    static String trimParagraphTail(String value, int maxTokens) {
        String normalized = requireNonNullElse(value, "").strip();
        if (maxTokens <= 0 || normalized.isEmpty()) {
            return "";
        }
        if (estimateTokens(normalized) <= maxTokens) {
            return normalized;
        }
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        var separators = PARAGRAPH_SEPARATOR.matcher(normalized);
        while (separators.find()) {
            starts.add(separators.end());
        }
        int tokens = 0;
        int asciiRun = 0;
        int cursor = normalized.length();
        int tailStart = normalized.length();
        // Scan each character at most once instead of estimating every growing suffix.
        for (int i = starts.size() - 1; i >= 0; i--) {
            int start = starts.get(i);
            while (cursor > start) {
                int codePoint = normalized.codePointBefore(cursor);
                cursor -= Character.charCount(codePoint);
                if (codePoint <= 0x7f) {
                    if (asciiRun++ % 4 == 0) {
                        tokens++;
                    }
                } else {
                    tokens++;
                    asciiRun = 0;
                }
                if (tokens > maxTokens) {
                    return normalized.substring(tailStart);
                }
            }
            tailStart = start;
        }
        return normalized.substring(tailStart);
    }
}
