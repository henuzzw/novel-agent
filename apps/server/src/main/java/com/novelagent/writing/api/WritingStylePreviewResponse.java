package com.novelagent.writing.api;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.domain.WritingStylePreviewContent;
import com.novelagent.writing.domain.WritingStyleProfile;
import java.util.UUID;

public record WritingStylePreviewResponse(
        UUID sourceOutlineVersionId,
        long sourceOutlineRowVersion,
        int outlineGenerationNumber,
        UUID sourceBibleVersionId,
        WritingStyleProfile profile,
        ModelProvider provider,
        int targetWords,
        String previewMode,
        WritingStylePreviewContent content) { }
