package com.novelagent.memory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryTextBudgetTest {
    @Test
    void estimatesAsciiRunsAndUnicodeCodePointsWithExistingRules() {
        assertThat(MemoryTextBudget.estimateTokens(null)).isZero();
        assertThat(MemoryTextBudget.estimateTokens(" \t\n\u3000")).isZero();
        assertThat(MemoryTextBudget.estimateTokens("abcd")).isEqualTo(1);
        assertThat(MemoryTextBudget.estimateTokens("abcde")).isEqualTo(2);
        assertThat(MemoryTextBudget.estimateTokens("abcd中efghi😀")).isEqualTo(5);
        assertThat(MemoryTextBudget.estimateTokens("😀𠮷中文")).isEqualTo(4);
        assertThat(MemoryTextBudget.estimateTokens("a 中 b")).isEqualTo(3);
    }

    @Test
    void reservesEllipsisEvenForOneTokenAndKeepsSurrogatePairsWhole() {
        assertThat(MemoryTextBudget.trimToTokens("很长的正文", 1)).isEqualTo("…");
        assertThat(MemoryTextBudget.trimToTokens("abcdefgh", 1)).isEqualTo("…");
        assertThat(MemoryTextBudget.trimToTokens("😀😀😀", 2)).isEqualTo("😀…");
        assertThat(MemoryTextBudget.trimToTokens("𠮷中文", 2)).isEqualTo("𠮷…");
        assertThat(MemoryTextBudget.trimToTokens("  abcd  ", 1)).isEqualTo("abcd");
        assertThat(MemoryTextBudget.trimToTokens("abcdef中长正文", 2)).isEqualTo("abcd…");
        assertThat(MemoryTextBudget.trimToTokens(null, 10)).isEmpty();
        assertThat(MemoryTextBudget.trimToTokens("正文", 0)).isEmpty();
        assertThat(MemoryTextBudget.trimToTokens("正文", -1)).isEmpty();
    }

    @Test
    void clippedPrefixesAlwaysFitAndRemainAnExactSourcePrefix() {
        for (String source : List.of("中英文 mixed words 😀𠮷 正文。".repeat(20), "abcdef ".repeat(30), "😀".repeat(80))) {
            for (int budget = 1; budget <= 40; budget++) {
                String clipped = MemoryTextBudget.trimToTokens(source, budget);
                assertThat(MemoryTextBudget.estimateTokens(clipped)).isLessThanOrEqualTo(budget);
                assertThat(clipped).endsWith("…");
                String prefix = clipped.substring(0, clipped.length() - 1);
                assertThat(source).startsWith(prefix);
                assertWellFormedUnicode(prefix);
            }
        }
    }

    @Test
    void selectsLongestCompleteParagraphSuffixAcrossMixedLineEndings() {
        List<String> paragraphs = List.of("开头中文😀", "abcde", "𠮷正文 mixed words", "结尾");
        for (String separator : List.of("\n", "\r\n\r\n", "\n \t\n", "\u2028")) {
            String source = String.join(separator, paragraphs);
            for (int budget = 0; budget <= 35; budget++) {
                String expected = "";
                if (budget > 0) {
                    for (int start = 0; start < paragraphs.size(); start++) {
                        String suffix = String.join(separator, paragraphs.subList(start, paragraphs.size()));
                        if (MemoryTextBudget.estimateTokens(suffix) <= budget) {
                            expected = suffix;
                            break;
                        }
                    }
                }
                assertThat(MemoryTextBudget.trimParagraphTail(source, budget))
                        .as("separator=%s, budget=%s", separator, budget).isEqualTo(expected);
            }
        }
    }

    @Test
    void omitsOversizedSingleParagraphAndKeepsLongDocumentEnding() {
        assertThat(MemoryTextBudget.trimParagraphTail("正文".repeat(1000), 20)).isEmpty();
        assertThat(MemoryTextBudget.trimParagraphTail(null, 20)).isEmpty();
        assertThat(MemoryTextBudget.trimParagraphTail("  短正文  ", 20)).isEqualTo("短正文");
        String source = "较早正文😀\r\n\r\n".repeat(2000) + "最终结尾。";
        String tail = MemoryTextBudget.trimParagraphTail(source, 80);
        assertThat(tail).startsWith("较早正文😀").endsWith("最终结尾。");
        assertThat(source).endsWith(tail);
        assertThat(MemoryTextBudget.estimateTokens(tail)).isLessThanOrEqualTo(80);
        assertWellFormedUnicode(tail);
    }

    private static void assertWellFormedUnicode(String text) {
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isHighSurrogate(character)) {
                assertThat(index + 1).isLessThan(text.length());
                assertThat(Character.isLowSurrogate(text.charAt(++index))).isTrue();
            } else {
                assertThat(Character.isLowSurrogate(character)).isFalse();
            }
        }
    }
}
