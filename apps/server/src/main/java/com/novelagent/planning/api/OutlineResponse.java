package com.novelagent.planning.api;

import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.OutlineWordBudget;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OutlineResponse(UUID id, UUID projectId, int generationNumber, String schemaVersion,
        OutlineStatus status, String generatorType, String authorInstruction, UUID sourceBibleVersionId,
        UUID baseOutlineVersionId,
        OutlineWordBudget wordBudget, OutlineContent content, List<String> changeSummary,
        long version, Instant createdAt, Instant updatedAt) {
    public static OutlineResponse from(OutlineVersion value) {
        return from(value, value.getContent());
    }

    public static OutlineResponse from(OutlineVersion value, OutlineContent content) {
        return new OutlineResponse(value.getId(), value.getProjectId(), value.getGenerationNumber(),
                value.getSchemaVersion(), value.getStatus(), value.getGeneratorType(), value.getAuthorInstruction(),
                value.getSourceBibleVersionId(), value.getBaseOutlineVersionId(), value.getWordBudget(),
                content, value.getChangeSummary(), value.getRowVersion(),
                value.getCreatedAt(), value.getUpdatedAt());
    }
}
