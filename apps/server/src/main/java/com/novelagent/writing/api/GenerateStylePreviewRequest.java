package com.novelagent.writing.api;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.domain.WritingStyleProfile;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record GenerateStylePreviewRequest(
        @NotNull UUID outlineVersionId,
        @NotNull @Min(0) Long expectedOutlineVersion,
        @NotNull WritingStyleProfile profile,
        @NotNull ModelProvider provider,
        @Min(300) @Max(1500) Integer targetWords,
        @Size(max = 1000) String instruction) {
    public int effectiveTargetWords() {
        return targetWords == null ? 800 : targetWords;
    }
}
