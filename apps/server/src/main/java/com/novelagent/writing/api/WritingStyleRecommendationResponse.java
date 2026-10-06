package com.novelagent.writing.api;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.domain.WritingStyleProfile;
import com.novelagent.writing.domain.WritingStyleRecommendationContent.Evidence;
import java.util.List;
import java.util.UUID;

public record WritingStyleRecommendationResponse(
        UUID sourceBibleVersionId,
        long sourceBibleRowVersion,
        int bibleGenerationNumber,
        ModelProvider provider,
        String recommendationMode,
        String summary,
        List<Recommendation> recommendations) {
    public record Recommendation(WritingStyleProfile profile, String reason, String tradeoff, List<Evidence> evidence) {
    }
}
