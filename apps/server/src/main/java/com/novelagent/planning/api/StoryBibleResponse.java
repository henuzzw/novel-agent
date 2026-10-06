package com.novelagent.planning.api;

import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StoryBibleResponse(UUID id, UUID projectId, int generationNumber, String schemaVersion,
        StoryBibleStatus status, String generatorType, String authorInstruction,
        UUID sourceDirectionSetId, UUID sourceCandidateId, UUID sourceImportId, UUID baseBibleVersionId,
        StoryBibleContent content,
        List<String> changeSummary, long version, Instant createdAt, Instant updatedAt) {
    public static StoryBibleResponse from(StoryBibleVersion value) {
        return from(value, value.getContent());
    }

    public static StoryBibleResponse from(StoryBibleVersion value, StoryBibleContent content) {
        return new StoryBibleResponse(value.getId(), value.getProjectId(), value.getGenerationNumber(),
                value.getSchemaVersion(), value.getStatus(), value.getGeneratorType(), value.getAuthorInstruction(),
                value.getSourceDirectionSetId(), value.getSourceCandidateId(), value.getSourceImportId(),
                value.getBaseBibleVersionId(), content,
                value.getChangeSummary(), value.getRowVersion(), value.getCreatedAt(), value.getUpdatedAt());
    }
}
