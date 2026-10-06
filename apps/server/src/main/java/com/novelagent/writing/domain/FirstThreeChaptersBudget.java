package com.novelagent.writing.domain;

public record FirstThreeChaptersBudget(int estimatedInputTokens, int maxOutputTokens,
        int contextWindowTokens, int safetyMarginTokens, int inputLimitTokens, boolean fits,
        int modelCalls, String notice) { }
