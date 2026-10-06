package com.novelagent.writing.api;

import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.ManuscriptVersion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ManuscriptResponse(UUID id, UUID projectId, UUID sourceContractVersionId,
        UUID baseManuscriptVersionId, UUID sourceReviewVersionId, int chapterNumber,
        int versionNumber, String schemaVersion, ManuscriptStatus status, String generatorType,
        String authorInstruction, ManuscriptContent content, List<String> changeSummary,
        long version, Instant createdAt, Instant updatedAt) {
    public static ManuscriptResponse from(ManuscriptVersion value) {
        return from(value, value.getContent());
    }

    public static ManuscriptResponse from(ManuscriptVersion value, ManuscriptContent content) {
        return new ManuscriptResponse(value.getId(), value.getProjectId(), value.getSourceContractVersionId(),
                value.getBaseManuscriptVersionId(), value.getSourceReviewVersionId(),
                value.getChapterNumber(), value.getVersionNumber(), value.getSchemaVersion(), value.getStatus(),
                value.getGeneratorType(), value.getAuthorInstruction(), content, value.getChangeSummary(), value.getRowVersion(),
                value.getCreatedAt(), value.getUpdatedAt());
    }
}
