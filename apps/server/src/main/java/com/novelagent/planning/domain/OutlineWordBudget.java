package com.novelagent.planning.domain;

public record OutlineWordBudget(
        int targetWords,
        int acceptableMinWords,
        int acceptableMaxWords,
        int recommendedVolumeCount,
        int recommendedChapterCount,
        int averageChapterWords,
        int recommendedChapterMinWords,
        int recommendedChapterMaxWords) {
}
