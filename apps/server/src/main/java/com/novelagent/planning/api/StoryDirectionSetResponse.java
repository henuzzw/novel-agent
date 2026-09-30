package com.novelagent.planning.api;

import com.novelagent.planning.domain.StoryDirectionCandidate;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.StoryDirectionSet;
import com.novelagent.planning.domain.StoryDirectionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StoryDirectionSetResponse(
        UUID id,
        UUID projectId,
        int generationNumber,
        String schemaVersion,
        StoryDirectionStatus status,
        String generatorType,
        String authorInstruction,
        long sourceIntentVersion,
        OutlineWordBudget wordBudget,
        List<StoryDirectionCandidate> directions,
        List<String> questionsForAuthor,
        List<String> changeSummary,
        UUID selectedCandidateId,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static StoryDirectionSetResponse from(StoryDirectionSet set, OutlineWordBudget wordBudget) {
        return new StoryDirectionSetResponse(
                set.getId(),
                set.getProjectId(),
                set.getGenerationNumber(),
                set.getSchemaVersion(),
                set.getStatus(),
                set.getGeneratorType(),
                set.getAuthorInstruction(),
                set.getInputSnapshot().sourceVersion(),
                wordBudget,
                set.getDirections(),
                set.getQuestionsForAuthor(),
                set.getChangeSummary(),
                set.getSelectedCandidateId(),
                set.getRowVersion(),
                set.getCreatedAt(),
                set.getUpdatedAt());
    }
}
