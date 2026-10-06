package com.novelagent.writing.domain;

import java.util.List;

public record WritingStyleProfile(String name, String narrativeVoice, String sentenceRhythm,
        String descriptionFocus, String dialogueStyle, String emotionalExpression, String pacing,
        List<String> avoidPatterns, String basePresetId, Integer basePresetVersion, WritingStyleCraft craft) {
    public WritingStyleProfile(String name, String narrativeVoice, String sentenceRhythm,
            String descriptionFocus, String dialogueStyle, String emotionalExpression, String pacing,
            List<String> avoidPatterns) {
        this(name, narrativeVoice, sentenceRhythm, descriptionFocus, dialogueStyle, emotionalExpression,
                pacing, avoidPatterns, null, null, null);
    }

    public WritingStyleProfile {
        require(name, 80);
        for (String value : List.of(narrativeVoice == null ? "" : narrativeVoice,
                sentenceRhythm == null ? "" : sentenceRhythm, descriptionFocus == null ? "" : descriptionFocus,
                dialogueStyle == null ? "" : dialogueStyle, emotionalExpression == null ? "" : emotionalExpression,
                pacing == null ? "" : pacing)) require(value, 600);
        if (avoidPatterns == null || avoidPatterns.size() > 12) throw new IllegalArgumentException("风格禁用表达最多 12 项");
        for (String pattern : avoidPatterns) require(pattern, 120);
        avoidPatterns = List.copyOf(avoidPatterns);
        if ((basePresetId == null) != (basePresetVersion == null)
                || basePresetId != null && (!basePresetId.matches("[a-z][a-z0-9-]{0,59}")
                || basePresetVersion < 1 || basePresetVersion > 10000)) {
            throw new IllegalArgumentException("基础风格标识与版本无效");
        }
    }

    public WritingStyleProfile withCraft(String id, int version, WritingStyleCraft value) {
        return new WritingStyleProfile(name, narrativeVoice, sentenceRhythm, descriptionFocus,
                dialogueStyle, emotionalExpression, pacing, avoidPatterns, id, version, value);
    }

    private static void require(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) throw new IllegalArgumentException("写作风格字段为空或超长");
    }
}
