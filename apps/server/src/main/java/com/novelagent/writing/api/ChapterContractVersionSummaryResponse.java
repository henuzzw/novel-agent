package com.novelagent.writing.api;

import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.domain.ChapterContractVersion;
import java.time.Instant;
import java.util.UUID;

public record ChapterContractVersionSummaryResponse(UUID id, int versionNumber, ChapterContractStatus status,
        UUID sourceOutlineVersionId, UUID baseContractVersionId, String chapterTitle, Instant createdAt) {
    public static ChapterContractVersionSummaryResponse from(ChapterContractVersion value, String title) {
        return new ChapterContractVersionSummaryResponse(value.getId(), value.getVersionNumber(), value.getStatus(),
                value.getSourceOutlineVersionId(), value.getBaseContractVersionId(), title, value.getCreatedAt());
    }
}
