package com.novelagent.writing.api;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.domain.QualityReviewContent;
import java.util.UUID;

public record StylePreviewReviewResponse(UUID id, WritingStylePreviewResponse preview,
        ModelProvider provider, String reviewMode, QualityReviewContent content,
        boolean revisionAttempted) { }
