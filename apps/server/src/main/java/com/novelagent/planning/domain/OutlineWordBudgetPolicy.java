package com.novelagent.planning.domain;

import org.springframework.stereotype.Component;

@Component
public class OutlineWordBudgetPolicy {

    private static final int CHAPTERS_PER_VOLUME = 20;
    private static final int TOTAL_TOLERANCE_WORDS = 10_000;

    public OutlineWordBudget plan(Integer targetWords) {
        if (targetWords == null || targetWords < 1000) {
            throw new IllegalArgumentException("生成大纲前必须设置不少于 1000 字的目标字数");
        }

        int preferredChapterWords = preferredChapterWords(targetWords);
        int chapterCount = Math.max(1, (int) Math.ceil(targetWords / (double) preferredChapterWords));
        int volumeCount = Math.max(1, (int) Math.ceil(chapterCount / (double) CHAPTERS_PER_VOLUME));
        int averageChapterWords = (int) Math.ceil(targetWords / (double) chapterCount);
        int chapterMin = roundToHundreds((int) Math.floor(averageChapterWords * 0.8));
        int chapterMax = roundToHundreds((int) Math.ceil(averageChapterWords * 1.2));

        OutlineWordBudget budget = new OutlineWordBudget(
                targetWords,
                Math.max(1_000, targetWords - TOTAL_TOLERANCE_WORDS),
                targetWords + TOTAL_TOLERANCE_WORDS,
                volumeCount,
                chapterCount,
                averageChapterWords,
                chapterMin,
                chapterMax);
        validate(budget);
        return budget;
    }

    public void validate(OutlineWordBudget budget) {
        if (budget.acceptableMinWords() >= budget.acceptableMaxWords()) {
            throw new IllegalArgumentException("作品字数浮动区间不合法");
        }
        if (budget.recommendedChapterMinWords() >= budget.recommendedChapterMaxWords()) {
            throw new IllegalArgumentException("章节建议字数区间不合法");
        }
    }

    private static int roundToHundreds(int words) {
        return Math.max(500, (int) Math.round(words / 100.0) * 100);
    }

    private static int preferredChapterWords(int targetWords) {
        if (targetWords <= 80_000) {
            return 2_500;
        }
        if (targetWords <= 200_000) {
            return 3_000;
        }
        if (targetWords <= 500_000) {
            return 3_500;
        }
        return 4_000;
    }
}
