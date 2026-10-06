package com.novelagent.writing.api;

import com.novelagent.writing.domain.WritingStylePreviewContent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record CheckStylePreviewRequest(
        @NotNull @Valid GenerateStylePreviewRequest source,
        @NotNull WritingStylePreviewContent content) { }
