package com.novelagent.canon.api;

import com.novelagent.canon.domain.CanonCommit;
import com.novelagent.writing.domain.FactProposal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CanonCommitResponse(UUID id, UUID projectId, int chapterNumber, UUID manuscriptVersionId,
        UUID reviewVersionId, long canonVersion, List<FactProposal> acceptedFacts, Instant createdAt) {

    public static CanonCommitResponse from(CanonCommit commit) {
        return new CanonCommitResponse(
                commit.getId(),
                commit.getProjectId(),
                commit.getChapterNumber(),
                commit.getManuscriptVersionId(),
                commit.getReviewVersionId(),
                commit.getCanonVersion(),
                commit.getAcceptedFacts(),
                commit.getCreatedAt());
    }
}
