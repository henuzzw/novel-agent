package com.novelagent.writing.domain;

import java.util.UUID;

public record StylePreviewSource(UUID outlineVersionId, long expectedOutlineVersion,
        WritingStyleProfile profile, String provider, int targetWords, String instruction,
        WritingStylePreviewContent content) {
    public StylePreviewSource {
        if (outlineVersionId == null || expectedOutlineVersion < 0 || profile == null || provider == null
                || targetWords < 300 || targetWords > 1500 || content == null
                || (instruction != null && instruction.length() > 1000)) {
            throw new IllegalArgumentException("试写检查来源不完整或超出限制");
        }
    }
}
