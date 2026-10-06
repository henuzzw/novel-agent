package com.novelagent.writing.api;

import com.novelagent.writing.domain.ChapterContractReviewContent;
import com.novelagent.writing.domain.ChapterContractReviewVersion;
import com.novelagent.writing.domain.ReviewStatus;
import java.time.Instant;
import java.util.UUID;

public record ChapterContractReviewResponse(UUID id, UUID projectId, int chapterNumber,
        UUID sourceContractVersionId, long sourceContractRowVersion, int versionNumber, ReviewStatus status,
        String generatorType, String authorInstruction, ChapterContractReviewContent content,
        long version, Instant createdAt, Instant updatedAt) {
    public static ChapterContractReviewResponse from(ChapterContractReviewVersion value) {
        return new ChapterContractReviewResponse(value.getId(), value.getProjectId(), value.getChapterNumber(),
                value.getSourceContractVersionId(), value.getSourceContractRowVersion(), value.getVersionNumber(),
                value.getStatus(), value.getGeneratorType(), value.getAuthorInstruction(), value.getContent(),
                value.getRowVersion(), value.getCreatedAt(), value.getUpdatedAt());
    }
}
