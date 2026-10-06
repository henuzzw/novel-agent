package com.novelagent.writing.api;

import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.domain.ChapterContractVersion;
import java.time.Instant;
import java.util.UUID;

public record ChapterContractResponse(UUID id, UUID projectId, UUID sourceOutlineVersionId,
        UUID baseContractVersionId, int chapterNumber,
        int versionNumber, String schemaVersion, ChapterContractStatus status, String generatorType,
        String authorInstruction, ChapterContractContent content, long version, Instant createdAt, Instant updatedAt) {
    public static ChapterContractResponse from(ChapterContractVersion value) {
        return new ChapterContractResponse(value.getId(), value.getProjectId(), value.getSourceOutlineVersionId(),
                value.getBaseContractVersionId(),
                value.getChapterNumber(), value.getVersionNumber(), value.getSchemaVersion(), value.getStatus(),
                value.getGeneratorType(), value.getAuthorInstruction(), value.getContent(), value.getRowVersion(),
                value.getCreatedAt(), value.getUpdatedAt());
    }
}
