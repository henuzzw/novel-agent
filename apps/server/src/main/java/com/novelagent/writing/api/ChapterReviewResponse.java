package com.novelagent.writing.api;

import com.novelagent.writing.domain.*;
import java.time.Instant;
import java.util.UUID;

public record ChapterReviewResponse(UUID id, UUID projectId, int chapterNumber, UUID sourceManuscriptVersionId,
        int versionNumber, String schemaVersion, ReviewStatus status, String generatorType,
        String authorInstruction, ChapterReviewContent content, long version, Instant createdAt, Instant updatedAt) {
    public static ChapterReviewResponse from(ChapterReviewVersion value) {
        return new ChapterReviewResponse(value.getId(), value.getProjectId(), value.getChapterNumber(),
                value.getSourceManuscriptVersionId(), value.getVersionNumber(), value.getSchemaVersion(),
                value.getStatus(), value.getGeneratorType(), value.getAuthorInstruction(), value.getContent(),
                value.getRowVersion(), value.getCreatedAt(), value.getUpdatedAt());
    }
}
