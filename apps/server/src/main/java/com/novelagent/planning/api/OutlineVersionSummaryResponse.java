package com.novelagent.planning.api;

import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.OutlineVersion;
import java.time.Instant;
import java.util.UUID;

public record OutlineVersionSummaryResponse(UUID id, int generationNumber, OutlineStatus status,
        String title, int chapterCount, UUID sourceBibleVersionId, UUID baseOutlineVersionId,
        Instant createdAt) {
    public static OutlineVersionSummaryResponse from(OutlineVersion version, String renderedTitle) {
        return new OutlineVersionSummaryResponse(version.getId(), version.getGenerationNumber(),
                version.getStatus(), renderedTitle, version.getContent().chapterCount(),
                version.getSourceBibleVersionId(), version.getBaseOutlineVersionId(),
                version.getCreatedAt());
    }
}
