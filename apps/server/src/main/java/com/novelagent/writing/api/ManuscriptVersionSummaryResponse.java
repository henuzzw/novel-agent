package com.novelagent.writing.api;

import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.ManuscriptVersion;
import java.time.Instant;
import java.util.UUID;

public record ManuscriptVersionSummaryResponse(UUID id, int versionNumber, ManuscriptStatus status,
        UUID sourceContractVersionId, UUID baseManuscriptVersionId, String title,
        int bodyLength, Instant createdAt) {
    public static ManuscriptVersionSummaryResponse from(ManuscriptVersion value, String title) {
        return new ManuscriptVersionSummaryResponse(value.getId(), value.getVersionNumber(), value.getStatus(),
                value.getSourceContractVersionId(), value.getBaseManuscriptVersionId(), title,
                value.getContent().body().length(), value.getCreatedAt());
    }
}
