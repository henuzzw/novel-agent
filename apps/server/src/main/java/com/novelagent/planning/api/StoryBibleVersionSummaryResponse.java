package com.novelagent.planning.api;

import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import java.time.Instant;
import java.util.UUID;

public record StoryBibleVersionSummaryResponse(UUID id, int generationNumber, StoryBibleStatus status,
        String logline, UUID baseBibleVersionId, Instant createdAt) {
    public static StoryBibleVersionSummaryResponse from(StoryBibleVersion version, String renderedLogline) {
        return new StoryBibleVersionSummaryResponse(version.getId(), version.getGenerationNumber(),
                version.getStatus(), renderedLogline, version.getBaseBibleVersionId(), version.getCreatedAt());
    }
}
